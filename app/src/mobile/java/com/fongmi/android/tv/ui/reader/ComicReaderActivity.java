package com.fongmi.android.tv.ui.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画阅读器：竖向滚动长图流。
 *
 * 设计要点：
 * 1. 图片一次性交给 RecyclerView，靠 Glide 的懒加载 + 视口回收控制内存；
 * 2. 图片地址经 ImgUtil.getUrl(picUrl, pageUrl) 处理防盗链（Referer/后缀参数），
 *    这是漫画能出图的关键——直接用原始 URL 多数站点会 403；
 * 3. 顶部悬浮章名与页码，点击屏幕中部切换显隐；
 * 4. 底部章节目录与翻章。
 */
public class ComicReaderActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "session_id";

    public static void start(Activity activity, ReadingSession session, int startPage) {
        if (session == null) return;
        session.startPage = Math.max(0, startPage);
        String id = ReadingSession.put(session);
        Intent it = new Intent(activity, ComicReaderActivity.class);
        it.putExtra(EXTRA_ID, id);
        activity.startActivity(it);
    }

    private ReadingSession session;
    private RecyclerView list;
    private ComicAdapter adapter;
    private TextView titleBar;
    private TextView pageBar;
    private LinearLayout bottomBar;
    private boolean uiVisible = true;
    /** 满屏适宽（true=自适应屏宽，false=原图尺寸可放大） */
    private boolean fitWidth = true;
    /** 全屏深浸 */
    private boolean isFs;
    /** 已在底部提示过（防连续弹） */
    private boolean bottomReached;
    /** 下部容器（翻章行 + 工具行） */
    private LinearLayout bottomWrap;
    /** 上部容器 */
    private LinearLayout topWrap;

    /** 自动滚屏 */
    private boolean autoScroll;
    private final android.os.Handler autoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long autoScrollIntervalMs = 60L;
    private int autoScrollStep;
    /** 阅读方向：false=纵向长条 true=横向翻页 */
    private boolean horizontal;
    /** 缩放级别（原图模式下有效） */
    private float zoom = 1f;
    /** 阅读设置持久化 */
    private android.content.SharedPreferences prefs;
    /** 正在拉本章图片（防重复请求） */
    private boolean loadingImages;
    /** 图片 Referer 兜底（防盗链图源需要） */
    private String referer = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPrefs();
        String id = getIntent().getStringExtra(EXTRA_ID);
        session = ReadingSession.get(id);
        if (session == null) {
            Toast.makeText(this, "\u4f1a\u8bdd\u5df2\u5931\u6548", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUi();
        // 图片 Referer：优先用会话里的，兜底用站点地址（防盗链必需）
        referer = session.referer == null ? "" : session.referer;
        if (TextUtils.isEmpty(referer) && !TextUtils.isEmpty(session.pageUrl)) {
            referer = session.pageUrl;
        }
        // 默认全屏沉浸：漫画是整屏看图，系统栏会占掉可视高度
        isFs = true;
        com.fongmi.android.tv.utils.Util.hideSystemUI(this);
        if (horizontal) applyDirection();
        loadChapter();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#121212"));

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setBackgroundColor(Color.parseColor("#121212"));
        adapter = new ComicAdapter();
        list.setAdapter(adapter);
        root.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部信息条
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xCC000000);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));

        titleBar = new TextView(this);
        titleBar.setTextColor(Color.WHITE);
        titleBar.setTextSize(15);
        titleBar.setSingleLine(true);
        titleBar.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titleBar);

        pageBar = new TextView(this);
        pageBar.setTextColor(0xFFAAAAAA);
        pageBar.setTextSize(12);
        pageBar.setPadding(0, dp(2), 0, 0);
        top.addView(pageBar);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topWrap = top;
        root.addView(top, topLp);

        // 底部操作条
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(0xCC000000);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomBar.addView(action("\u4e0a\u4e00\u7ae0", v -> switchChapter(-1)));
        bottomBar.addView(action("\u76ee\u5f55", v -> showChapters()));
        bottomBar.addView(action("\u4e0b\u4e00\u7ae0", v -> switchChapter(1)));

        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setBackgroundColor(0xCC000000);
        toolBar.setPadding(dp(8), 0, dp(8), dp(8));
        toolBar.addView(action("\u8df3\u9875", v -> showPageJump()));
        toolBar.addView(action("\u5bbd\u5ea6", v -> toggleFitWidth()));
        toolBar.addView(action("\u5168\u5c4f", v -> toggleFullscreen()));
        toolBar.addView(action("\u66f4\u591a", v -> showComicMore()));

        // 两块合成一个容器再加进 root：同一个 View 只能有一个父容器
        LinearLayout botWrap = new LinearLayout(this);
        botWrap.setOrientation(LinearLayout.VERTICAL);
        botWrap.addView(bottomBar);
        botWrap.addView(toolBar);
        bottomWrap = botWrap;

        FrameLayout.LayoutParams botWrapLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(botWrap, botWrapLp);

        // 轻点正文收起/展开上下栏
        list.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float dx0, dy0;
            private long t0;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN -> {
                        dx0 = e.getX();
                        dy0 = e.getY();
                        t0 = System.currentTimeMillis();
                    }
                    case android.view.MotionEvent.ACTION_UP -> {
                        float ddx = Math.abs(e.getX() - dx0);
                        float ddy = Math.abs(e.getY() - dy0);
                        if (ddx < dp(12) && ddy < dp(12) && System.currentTimeMillis() - t0 < 300) togglePanel();
                    }
                    default -> {
                    }
                }
                return false;
            }
        });

        // 滚到底部自动衔接下一章
        list.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dxv, int dyv) {
                // 实时显示“第 n / 总 张”，长图翻到哪一目了然
                if (!horizontal) {
                    androidx.recyclerview.widget.LinearLayoutManager lm =
                            (androidx.recyclerview.widget.LinearLayoutManager) rv.getLayoutManager();
                    if (lm != null) {
                        int first = lm.findFirstVisibleItemPosition() + 1;
                        int total = adapter.getItemCount();
                        if (total > 0) pageBar.setText(chapterLabel() + "   " + first + "/" + total);
                    }
                }
                if (!rv.canScrollVertically(1) && dyv > 0) onReachBottom();
            }
        });
        setContentView(root);
    }

    private TextView action(String text, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(10), 0, dp(10));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 加载当前章图片 */
    private void loadChapter() {
        if (session == null) return;
        titleBar.setText(session.bookTitle);
        List<String> imgs = resolveImages();
        adapter.submit(imgs, session.pageUrl);
        pageBar.setText(chapterLabel() + "   " + imgs.size() + " \u5f20");
        int start = Math.max(0, Math.min(session.startPage, imgs.size() - 1));
        if (start > 0) list.scrollToPosition(start);
        saveProgress(imgs.size());
        // 图片为空：按章节地址现取（切章后最常见，会话里不会随章带图）
        if (imgs.isEmpty()) requestChapterImages();
    }

    /**
     * 按当前章地址现取图片列表。
     *
     * <p>漫画源的 playerContent 会返回 {@code pics://} 串（或多图 URL），
     * 切章后必须重新拉一次，否则新章永远是空白。</p>
     */
    private void requestChapterImages() {
        ReadingSession.Chapter c = session.currentChapter();
        String url = c == null ? "" : c.url;
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "本章没有地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String key = session.siteKey == null ? "" : session.siteKey;
        String flag = session.playFlag == null ? "" : session.playFlag;
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "缺少源标识", Toast.LENGTH_SHORT).show();
            return;
        }
        if (loadingImages) return;
        loadingImages = true;
        pageBar.setText("正在加载本章图片…");

        com.fongmi.android.tv.utils.Task.execute(() -> {
            List<String> out = new ArrayList<>();
            String err = null;
            try {
                com.fongmi.android.tv.bean.Result r =
                        com.fongmi.android.tv.api.SiteApi.playerContent(key, flag, url);
                if (r != null && r.getUrl() != null) {
                    // 复用判别层的拆分口径（支持 && / # / 逗号 / 换行），
                    // 否则推图网那种 "&&".join 串会当成一个超长 URL。
                    out.addAll(com.fongmi.android.tv.read.ReadingGate.splitImages(r.getUrl().v()));
                }
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final List<String> imgs = out;
            final String finalErr = err;
            runOnUiThread(() -> {
                loadingImages = false;
                if (isFinishing() || isDestroyed()) return;
                if (imgs.isEmpty()) {
                    pageBar.setText(chapterLabel() + "   加载失败"
                            + (TextUtils.isEmpty(finalErr) ? "" : "：" + finalErr));
                    return;
                }
                session.images = imgs;
                adapter.submit(imgs, session.pageUrl);
                pageBar.setText(chapterLabel() + "   " + imgs.size() + " \u5f20");
                list.scrollToPosition(0);
                saveProgress(imgs.size());
            });
        });
    }

    /** 从播放地址里拆图片列表（统一走判别层口径，避免两处不一致）。 */
    private List<String> splitImages(String raw) {
        return com.fongmi.android.tv.read.ReadingGate.splitImages(raw);
    }

    /** 图片列表：会话自带优先；为空则尝试从章节 pic 字段拆 */
    private List<String> resolveImages() {
        List<String> result = new ArrayList<>();
        if (session.images != null) {
            for (String s : session.images) if (!TextUtils.isEmpty(s)) result.add(s);
        }
        if (result.isEmpty() && session.chapters != null && !session.chapters.isEmpty()) {
            ReadingSession.Chapter c = session.currentChapter();
            if (c != null && !TextUtils.isEmpty(c.pic)) {
                for (String s : c.pic.split("[,\\n]")) {
                    String t = s.trim();
                    if (t.startsWith("http") || t.startsWith("//")) result.add(t);
                }
            }
        }
        return result;
    }

    private String chapterLabel() {
        int total = session.chapters == null ? 0 : session.chapters.size();
        if (total <= 0) return "";
        ReadingSession.Chapter c = session.currentChapter();
        String name = c == null || TextUtils.isEmpty(c.name) ? ("\u7b2c" + (session.chapterIndex + 1) + "\u7ae0") : c.name;
        return name + "  (" + (session.chapterIndex + 1) + "/" + total + ")";
    }

    private void switchChapter(int delta) {
        if (session.chapters == null) return;
        int target = session.chapterIndex + delta;
        if (target < 0 || target >= session.chapters.size()) {
            Toast.makeText(this, delta > 0 ? "\u5df2\u662f\u6700\u540e\u4e00\u7ae0" : "\u5df2\u662f\u7b2c\u4e00\u7ae0", Toast.LENGTH_SHORT).show();
            return;
        }
        session.chapterIndex = target;
        session.startPage = 0;
        session.images = new ArrayList<>();
        loadChapter();
        list.scrollToPosition(0);
    }

    private void showChapters() {
        if (session.chapters == null || session.chapters.isEmpty()) {
            Toast.makeText(this, "\u65e0\u7ae0\u8282\u5217\u8868", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[session.chapters.size()];
        for (int i = 0; i < names.length; i++) {
            ReadingSession.Chapter c = session.chapters.get(i);
            names[i] = TextUtils.isEmpty(c.name) ? ("\u7b2c" + (i + 1) + "\u7ae0") : c.name;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("\u76ee\u5f55")
                .setSingleChoiceItems(names, session.chapterIndex, (d, which) -> {
                    session.chapterIndex = which;
                    session.startPage = 0;
                    session.images = new ArrayList<>();
                    loadChapter();
                    list.scrollToPosition(0);
                    d.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    // ==================== 全屏 / 跳页 / 宽度 / 衔接 ====================

    /** 点正文收起/展开上下栏。 */
    private void togglePanel() {
        boolean show = topWrap.getVisibility() != View.VISIBLE;
        topWrap.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomWrap.setVisibility(show ? View.VISIBLE : View.GONE);
        uiVisible = show;
    }

    /** 全屏深浸：隐藏状态栏与导航栏，横屏漫画看得更全。 */
    private void toggleFullscreen() {
        if (isFs) {
            isFs = false;
            com.fongmi.android.tv.utils.Util.showSystemUI(this);
        } else {
            isFs = true;
            com.fongmi.android.tv.utils.Util.hideSystemUI(this);
        }
    }

    /** 切换适宽 / 原图（原图模式可以双指放大看细节）。 */
    private void toggleFitWidth() {
        fitWidth = !fitWidth;
        adapter.notifyDataSetChanged();
        Toast.makeText(this, fitWidth ? "适宽" : "原图", Toast.LENGTH_SHORT).show();
    }

    /** 跳到指定页。 */
    private void showPageJump() {
        int total = adapter.getItemCount();
        if (total <= 0) {
            Toast.makeText(this, "无图片", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + total);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("跳页")
                .setView(input)
                .setPositiveButton("跳转", (d, w) -> {
                    int target = 1;
                    try {
                        target = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable ignored) {
                    }
                    target = Math.max(1, Math.min(target, total));
                    list.scrollToPosition(target - 1);
                    session.startPage = target - 1;
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 滚到本章底部：提示衔接下一章，不自动跳（防误触）。 */
    private void onReachBottom() {
        if (bottomReached) return;
        boolean hasNext = session.chapters != null
                && session.chapterIndex < session.chapters.size() - 1;
        if (!hasNext) return;
        bottomReached = true;
        Toast.makeText(this, "已到本章末尾，可点「下一章」继续", Toast.LENGTH_SHORT).show();
        list.postDelayed(() -> bottomReached = false, 1500);
    }

    // ==================== 自动滚屏 / 方向 / 设置 ====================

    /** 自动滚屏开关：每 60ms 滚一小段，读长图不用手划。 */
    private void toggleAutoScroll() {
        if (autoScroll) {
            stopAutoScroll();
            Toast.makeText(this, "已停止自动滚屏", Toast.LENGTH_SHORT).show();
            return;
        }
        if (horizontal) {
            Toast.makeText(this, "横向翻页不支持自动滚屏", Toast.LENGTH_SHORT).show();
            return;
        }
        autoScroll = true;
        autoScrollStep = Math.max(2, dp(2));
        autoHandler.postDelayed(autoTick, autoScrollIntervalMs);
        Toast.makeText(this, "自动滚屏中", Toast.LENGTH_SHORT).show();
    }

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!autoScroll || isFinishing() || isDestroyed()) {
                stopAutoScroll();
                return;
            }
            if (!list.canScrollVertically(1)) {
                // 到底了，自动切下一章
                if (session.chapters != null && session.chapterIndex < session.chapters.size() - 1) {
                    switchChapter(1);
                } else {
                    stopAutoScroll();
                    Toast.makeText(ComicReaderActivity.this, "已是最后一章", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                list.scrollBy(0, autoScrollStep);
            }
            autoHandler.postDelayed(this, autoScrollIntervalMs);
        }
    };

    private void stopAutoScroll() {
        autoScroll = false;
        autoHandler.removeCallbacksAndMessages(null);
    }

    /** 切纵向长条 / 横向翻页。 */
    private void toggleDirection() {
        horizontal = !horizontal;
        stopAutoScroll();
        applyDirection();
        savePrefs();
        Toast.makeText(this, horizontal ? "横向翻页" : "纵向长条", Toast.LENGTH_SHORT).show();
    }

    /** 按 horizontal 重建 LayoutManager。 */
    private void applyDirection() {
        if (horizontal) {
            androidx.recyclerview.widget.LinearLayoutManager lm =
                    new androidx.recyclerview.widget.LinearLayoutManager(this,
                            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
            // 横向翻页启用吸附，一屏一页
            new androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(list);
            list.setLayoutManager(lm);
        } else {
            androidx.recyclerview.widget.LinearLayoutManager lm =
                    new androidx.recyclerview.widget.LinearLayoutManager(this, TOOL_VERTICAL, false);
            list.setLayoutManager(lm);
        }
        adapter.notifyDataSetChanged();
    }

    /** 横向模式下图片占满一屏，纵向模式自适应高度。 */
    private static final int TOOL_HORIZONTAL = androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL;
    private static final int TOOL_VERTICAL = androidx.recyclerview.widget.LinearLayoutManager.VERTICAL;

    /** 漫画阅读设置面板。 */
    private void showComicSettings() {
        final String[] speeds = {"慢", "中", "快"};
        final long[] intervals = {100L, 60L, 30L};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("漫画设置")
                .setItems(new String[]{"自动滚屏速度", "翻到第一页"}, (d, w) -> {
                    if (w == 0) {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("滚屏速度")
                                .setItems(speeds, (dd, ww) -> {
                                    autoScrollIntervalMs = intervals[ww];
                                    savePrefs();
                                    if (autoScroll) {
                                        stopAutoScroll();
                                        toggleAutoScroll();
                                    }
                                })
                                .show();
                    } else {
                        list.scrollToPosition(0);
                    }
                })
                .show();
    }

    // ==================== 设置持久化 ====================

    private void loadPrefs() {
        try {
            prefs = getSharedPreferences("comic_reader", MODE_PRIVATE);
            fitWidth = prefs.getBoolean("fit_width", true);
            horizontal = prefs.getBoolean("horizontal", false);
            autoScrollIntervalMs = prefs.getLong("auto_interval", 60L);
        } catch (Throwable ignored) {
        }
    }

    private void savePrefs() {
        try {
            if (prefs == null) prefs = getSharedPreferences("comic_reader", MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("fit_width", fitWidth)
                    .putBoolean("horizontal", horizontal)
                    .putLong("auto_interval", autoScrollIntervalMs)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

/**
     * 漫画「更多」菜单。
     *
     * <p>工具栏只留高频项（跳页 / 宽度 / 全屏 / 更多），其余收纳进来，
     * 后续加功能也不会把工具栏撑爆。</p>
     */
    private void showComicMore() {
        String[] items = {
                "自动滚屏",
                "阅读方向（纵向 / 横向）",
                "滚屏速度",
                "缩放到适应",
                "复制当前页地址",
                "回到本章第一张",
                "重新加载本章"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0 -> toggleAutoScroll();
                        case 1 -> toggleDirection();
                        case 2 -> showComicSettings();
                        case 3 -> {
                            zoom = 1f;
                            fitWidth = true;
                            adapter.notifyDataSetChanged();
                            Toast.makeText(this, "已恢复适应屏宽", Toast.LENGTH_SHORT).show();
                        }
                        case 4 -> copyCurrentPage();
                        case 5 -> list.scrollToPosition(0);
                        case 6 -> {
                            session.images = new ArrayList<>();
                            loadChapter();
                        }
                        default -> {
                        }
                    }
                })
                .show();
    }

    /** 复制当前可见页的图片地址（排障 / 分享用）。 */
    private void copyCurrentPage() {
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return;
        int pos = lm.findFirstVisibleItemPosition();
        java.util.List<String> urls = adapter.snapshot();
        if (pos < 0 || pos >= urls.size()) {
            Toast.makeText(this, "当前页无地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("comic", urls.get(pos)));
            Toast.makeText(this, "已复制第 " + (pos + 1) + " 张地址", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 长按存图：用 Glide 拿到 Bitmap 后写入相册。 */
    private void saveImage(String url, int pageNo) {
        if (TextUtils.isEmpty(url)) return;
        Toast.makeText(this, "正在保存第 " + pageNo + " 页…", Toast.LENGTH_SHORT).show();
        try {
            com.bumptech.glide.Glide.with(this)
                    .asBitmap()
                    .load(url)
                    .into(new SaveImageTarget(this, pageNo));
        } catch (Throwable e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 保存图片的 Glide 目标（命名类，避开匿名泛型推断问题）。 */
    private static class SaveImageTarget
            extends com.bumptech.glide.request.target.SimpleTarget<android.graphics.Bitmap> {

        private final NovelHolder holder;
        private final int pageNo;

        private static class NovelHolder {
            final java.lang.ref.WeakReference<ComicReaderActivity> activity;

            NovelHolder(ComicReaderActivity a) {
                activity = new java.lang.ref.WeakReference<>(a);
            }
        }

        SaveImageTarget(ComicReaderActivity activity, int pageNo) {
            super();
            this.holder = new NovelHolder(activity);
            this.pageNo = pageNo;
        }

        @Override
        public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap bm,
                                    @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> tr) {
            ComicReaderActivity act = holder.activity.get();
            if (act == null || act.isFinishing()) return;
            String msg;
            try {
                String name = "comic_" + System.currentTimeMillis() + "_" + pageNo + ".png";
                java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES), "YsBox");
                if (!dir.exists()) dir.mkdirs();
                java.io.File out = new java.io.File(dir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                msg = "已保存到相册/YsBox/" + name;
            } catch (Throwable e) {
                msg = "保存失败：" + e.getMessage();
            }
            final String m = msg;
            act.runOnUiThread(() -> Toast.makeText(act, m, Toast.LENGTH_LONG).show());
        }
    }

    /** 存进度，回书架能续读 */
    private void saveProgress(int total) {
        try {
            ReadingStore.Record r = new ReadingStore.Record();
            r.kind = "COMIC";
            r.sourceName = session.sourceName;
            r.bookTitle = session.bookTitle;
            r.coverUrl = session.coverUrl;
            r.chapterIndex = session.chapterIndex;
            ReadingSession.Chapter c = session.currentChapter();
            if (c != null) {
                r.chapterName = c.name;
                r.chapterUrl = c.url;
            }
            r.siteKey = session.siteKey;
            r.detailId = session.detailId;
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // 音量键翻页：横屏看漫画时单手也能翻
        androidx.recyclerview.widget.LinearLayoutManager lm =
                list == null ? null : (androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return super.onKeyDown(keyCode, event);
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            stepItem(1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            stepItem(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 上下翻一张；到末尾自动衔接下一章。 */
    private void stepItem(int delta) {
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return;
        int cur = lm.findFirstVisibleItemPosition();
        int next = cur + delta;
        int total = adapter.getItemCount();
        if (next < 0) {
            if (session != null && session.chapterIndex > 0) switchChapter(-1);
            else Toast.makeText(this, "已是第一张", Toast.LENGTH_SHORT).show();
            return;
        }
        if (next >= total) {
            if (session != null && session.chapters != null
                    && session.chapterIndex < session.chapters.size() - 1) switchChapter(1);
            else Toast.makeText(this, "已是最后一张", Toast.LENGTH_SHORT).show();
            return;
        }
        list.scrollToPosition(next);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoScroll();
        savePrefs();
        if (session != null) saveProgress(adapter.getItemCount());
    }

    @Override
    protected void onDestroy() {
        autoHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 长图列表 */
    private class ComicAdapter extends RecyclerView.Adapter<ComicAdapter.Holder> {

        private final List<String> urls = new ArrayList<>();
        private String pageUrl;

        void submit(List<String> data, String pageUrl) {
            urls.clear();
            urls.addAll(data);
            this.pageUrl = pageUrl;
            notifyDataSetChanged();
        }

        /** 当前图片列表副本（复制地址 / 外部消费用，避免外部直接改内部表）。 */
        List<String> snapshot() {
            return new ArrayList<>(urls);
        }

        @androidx.annotation.NonNull
        @Override
        public Holder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            FrameLayout box = new FrameLayout(parent.getContext());
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView iv = new ImageView(parent.getContext());
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ProgressBar pb = new ProgressBar(parent.getContext());
            pb.setIndeterminate(true);
            FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
            box.addView(pb, pbLp);

            return new Holder(box, iv, pb);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull Holder h, int position) {
            String url = urls.get(position);
            h.pb.setVisibility(View.VISIBLE);
            // 适宽：图片自适应屏宽；原图：保留原尺寸，超出屏幕可横向滑动
            h.iv.setScaleType(fitWidth ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER);
            // 横向翻页：每张图占满一屏；纵向：高度自适应
            if (horizontal) {
                h.box.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                h.iv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                h.box.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                h.iv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            try {
                // 漫画必须 FIT_CENTER（vod=false）：CENTER_CROP 会把长图裁掉上下部分。
                // referer 用站点地址兜底，防盗链图源（推图网等）没有它会取不到图。
                ImgUtil.load("", url, h.iv, false, 0, 0, TextUtils.isEmpty(pageUrl) ? referer : pageUrl);
            } catch (Throwable e) {
                h.pb.setVisibility(View.GONE);
                return;
            }
            h.iv.postDelayed(() -> h.pb.setVisibility(View.GONE), 400);
            // 长按保存到相册
            h.iv.setOnLongClickListener(v -> {
                saveImage(url, position + 1);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final View box;
            final ImageView iv;
            final ProgressBar pb;

            Holder(View item, ImageView iv, ProgressBar pb) {
                super(item);
                this.box = item;
                this.iv = iv;
                this.pb = pb;
            }
        }
    }
}
