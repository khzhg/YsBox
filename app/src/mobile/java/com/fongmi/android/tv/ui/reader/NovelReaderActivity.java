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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.read.ReadingSession;

import org.json.JSONObject;

/**
 * 小说阅读器。
 *
 * 正文来源三层，按优先级：
 * <ol>
 *   <li>novel:// 载荷自带：地址形如 novel://{title,content,id,vodPlayUrl}，
 *       直接拆 JSON 渲染，一次网络请求都不发（对齐影视+ 的约定）。</li>
 *   <li>session.text 已带正文（判别层识别出的纯文本）。</li>
 *   <li>都没有 → 拿章节地址二次调 playerContent 现取。</li>
 * </ol>
 *
 * 排版要点：\n 是转义序列而非字面量，先反转义再按行切片，
 * 每段首行缩进两字，段间留白，避免「\n当……」这种字面量出现在正文里。
 */
public class NovelReaderActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "session_id";
    private static final String SCHEME_NOVEL = "novel://";

    private static final int BG = 0xFFF5F2E8;
    private static final int TEXT = 0xFF333333;
    private static final int HEAD = 0xFF222222;
    private static final int HINT = 0xFF888888;
    private static final int LINK = 0xFF1565C0;

    public static void start(Activity activity, ReadingSession session, int startFraction) {
        if (session == null) return;
        session.startFraction = startFraction;
        String id = ReadingSession.put(session);
        Intent it = new Intent(activity, NovelReaderActivity.class);
        it.putExtra(EXTRA_ID, id);
        activity.startActivity(it);
    }

    private ReadingSession session;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView titleBar;
    private TextView chapterBar;
    private TextView progressText;
    private SeekBar progressBar;
    private View topPanel;
    private View bottomPanel;
    private boolean suppressProgress;
    /** 当前章正文是否已解析完毕（防止重复请求） */
    private boolean loading;
    /** 正文字号（sp），调节后立即重排，不重拉数据 */
    private float fontSize = 17f;
    /** 夜间模式 */
    private boolean night;
    /** 当前章已解析的正文，调字号 / 切主题时复用 */
    private String renderedText = "";

    /** 翻页模式（true=左右分页，false=上下滚动） */
    /** 分页后的页段列表（每页一段文字） */
    private final java.util.List<String> pages = new java.util.ArrayList<>();
    /** 当前页下标 */
    private int pageIndex;
    /** 翻页模式下展示正文的 TextView */
    private TextView pageView;
    /** 屏幕尺寸（分页计算用） */
    private int viewW;
    private int viewH;
    /** 内容消费者：抬手切换上下栏 / 左右翻页 */
    private float downX;
    private float downY;
    private long downTime;
    /** 全屏深浸状态 */
    private boolean isFs;
    /** 翻页模式：0=上下滚动 1=左右滑动翻页 2=瞬切翻页 */
    private int pageMode = 1;

    /** 是否翻页模式（非滚动）。 */
    private boolean pagedMode() {
        return pageMode != 0;
    }
    /** 自动翻页 */
    private boolean autoPlaying;
    private final android.os.Handler autoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 自动翻页间隔（毫秒） */
    private long autoIntervalMs = 20000L;
    /** 阅读设置持久化 */
    private android.content.SharedPreferences prefs;

    /** TTS 朗读 */
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private boolean speaking;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = ReadingSession.get(getIntent().getStringExtra(EXTRA_ID));
        if (session == null) {
            Toast.makeText(this, "会话已失效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadPrefs();
        buildUi();
        // 默认全屏沉浸：阅读器本就是“沉浸阅读”场景，进来即隐藏系统栏
        isFs = true;
        com.fongmi.android.tv.utils.Util.hideSystemUI(this);
        applyPageMode();
        loadChapter();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(26), dp(22), dp(90));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 翻页容器：与滚动容器叠放，同一时刻只显示一个
        pageView = new TextView(this);
        pageView.setBackgroundColor(BG);
        pageView.setTextSize(fontSize);
        pageView.setTextColor(TEXT);
        pageView.setLineSpacing(dp(fontSize * 0.55f * lineSpacing), 1f);
        pageView.setPadding(dp(22), dp(30), dp(22), dp(70));
        pageView.setVisibility(View.GONE);
        root.addView(pageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部：书名 + 章节名 + 进度，常驻但不遮挡正文
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xF2000000);
        top.setPadding(dp(16), dp(12), dp(16), dp(10));

        titleBar = new TextView(this);
        titleBar.setTextColor(Color.WHITE);
        titleBar.setTextSize(16);
        titleBar.setSingleLine(true);
        titleBar.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titleBar);

        chapterBar = new TextView(this);
        chapterBar.setTextColor(0xFFBBBBBB);
        chapterBar.setTextSize(12);
        chapterBar.setPadding(0, dp(3), 0, 0);
        chapterBar.setSingleLine(true);
        chapterBar.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(chapterBar);

        topPanel = top;
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 底部：进度条 + 翻章 + 关目录
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xF2000000);
        bottom.setPadding(dp(16), dp(6), dp(16), dp(6));

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);

        progressBar = new SeekBar(this);
        progressBar.setMax(100);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) jumpTo(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                suppressProgress = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                suppressProgress = false;
            }
        });
        progressRow.addView(progressBar,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        progressText = new TextView(this);
        progressText.setTextColor(Color.WHITE);
        progressText.setTextSize(12);
        progressText.setPadding(dp(10), 0, 0, 0);
        progressText.setText("0%");
        progressRow.addView(progressText);
        bottom.addView(progressRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(action("上一章", v -> switchChapter(-1)));
        btnRow.addView(action("目录", v -> showChapters()));
        btnRow.addView(action("下一章", v -> switchChapter(1)));
        bottom.addView(btnRow);

        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.addView(action("A-", v -> changeFontSize(-1)));
        toolRow.addView(action("夜间", v -> toggleNight()));
        toolRow.addView(action("A+", v -> changeFontSize(1)));
        toolRow.addView(action("模式", v -> showPageModePicker()));
        bottom.addView(toolRow);

        LinearLayout toolRow2 = new LinearLayout(this);
        toolRow2.setOrientation(LinearLayout.HORIZONTAL);
        toolRow2.addView(action("自动", v -> toggleAutoPage()));
        toolRow2.addView(action("间隔", v -> showAutoSpeed()));
        toolRow2.addView(action("全屏", v -> toggleFullscreen()));
        toolRow2.addView(action("更多", v -> showMoreMenu()));
        bottom.addView(toolRow2);

        bottomPanel = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        scroll.getViewTreeObserver().addOnScrollChangedListener(this::onScroll);
        scroll.setOnTouchListener(this::onReaderTouch);
        pageView.setOnTouchListener(this::onReaderTouch);

        // 内容区尺寸就绪后才能精确分页
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w == viewW && h == viewH) return;
            viewW = w;
            viewH = h;
            if (pagedMode() && !TextUtils.isEmpty(renderedText)) paginate();
        });

        setContentView(root);
    }

    /** 字号增减，重排当前章（不重拉数据）。 */
    private void changeFontSize(int delta) {
        float next = fontSize + delta;
        if (next < 13f) next = 13f;
        if (next > 26f) next = 26f;
        if (next == fontSize) return;
        fontSize = next;
        if (!TextUtils.isEmpty(renderedText)) {
            int keep = progressBar == null ? 0 : progressBar.getProgress();
            renderText(renderedText);
            if (keep > 0) scroll.post(() -> jumpTo(keep));
        }
        Toast.makeText(this, "字号 " + (int) fontSize, Toast.LENGTH_SHORT).show();
    }

    /** 日间 / 夜间切换。 */
    private void toggleNight() {
        themeIndex = (themeIndex + 1) % 4;
        night = themeIndex == 3;
        applyTheme();
        if (!TextUtils.isEmpty(renderedText)) {
            int keep = progressBar == null ? 0 : progressBar.getProgress();
            renderText(renderedText);
            if (keep > 0) scroll.post(() -> jumpTo(keep));
        }
    }

    /** 内容区配色（夜间模式不刷白，地下室也能看）。 */
    private void applyTheme() {
        int bg;
        int fg;
        switch (themeIndex) {
            case 1 -> { bg = 0xFFFFFFFF; fg = 0xFF333333; }
            case 2 -> { bg = 0xFFCCE8CF; fg = 0xFF2F4F3F; }
            case 3 -> { bg = 0xFF1C1C1C; fg = 0xFFBFBFBF; }
            default -> { bg = BG; fg = TEXT; }
        }
        bgColor = bg;
        fgColor = fg;
        if (scroll != null) scroll.setBackgroundColor(bg);
        if (pageView != null) {
            pageView.setBackgroundColor(bg);
            pageView.setTextColor(fg);
        }
        View root = scroll == null ? null : (View) scroll.getParent();
        if (root != null) root.setBackgroundColor(bg);
    }

    // ==================== 翻页 / 全屏 ====================

    /** 轮换三种阅读模式：上下滚动 → 左右翻页 → 瞬切翻页 → 回到滚动。 */
    private void cyclePageMode() {
        pageMode = (pageMode + 1) % 3;
        applyPageMode();
        savePrefs();
    }

    /**
     * 「更多」菜单：把次要功能收纳进来，避免工具栏挤成一排小字。
     *
     * <p>工具栏只留高频操作（字号、主题、模式、自动、全屏），
     * 其余入口集中到这里，既清爽又便于继续加功能。</p>
     */
    private void showMoreMenu() {
        String[] items = {
                "阅读设置（主题 / 行距）",
                "本章搜索",
                "朗读本页",
                "跳到指定章",
                "复制本章正文",
                "护眼提醒（每 30 分钟）",
                "回到本章开头"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0 -> showReaderSettings();
                        case 1 -> showSearch();
                        case 2 -> toggleTts();
                        case 3 -> showJumpToChapter();
                        case 4 -> copyChapter();
                        case 5 -> toggleEyeCare();
                        case 6 -> {
                            pageIndex = 0;
                            if (pagedMode()) showPage();
                            else scroll.post(() -> scroll.scrollTo(0, 0));
                        }
                        default -> {
                        }
                    }
                })
                .show();
    }

    /** 跳到指定章号（长书按编号跳比翻目录快）。 */
    private void showJumpToChapter() {
        if (session.chapters == null || session.chapters.isEmpty()) {
            Toast.makeText(this, "无章节列表", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + session.chapters.size());
        input.setText(String.valueOf(session.chapterIndex + 1));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("跳到指定章")
                .setView(input)
                .setPositiveButton("跳转", (d, w) -> {
                    int n = 1;
                    try {
                        n = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable ignored) {
                    }
                    openChapter(Math.max(0, Math.min(n - 1, session.chapters.size() - 1)));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 复制本章正文到剪贴板（存笔记 / 分享用）。 */
    private void copyChapter() {
        String text = unescape(renderedText);
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "本章无正文", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("novel", text));
            Toast.makeText(this, "已复制本章（" + text.length() + " 字）", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 护眼提醒 ====================

    private boolean eyeCare;
    private long lastEyeCareAt;
    private final android.os.Handler eyeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable eyeTick = new Runnable() {
        @Override
        public void run() {
            if (!eyeCare || isFinishing()) return;
            com.fongmi.android.tv.utils.Notify.show("已经阅读 30 分钟，起来活动一下、看看远处吧 🌿");
            eyeHandler.postDelayed(this, 30 * 60 * 1000L);
        }
    };

    /** 护眼提醒：每 30 分钟提示一次远眺。 */
    private void toggleEyeCare() {
        eyeCare = !eyeCare;
        eyeHandler.removeCallbacks(eyeTick);
        if (eyeCare) {
            lastEyeCareAt = System.currentTimeMillis();
            eyeHandler.postDelayed(eyeTick, 30 * 60 * 1000L);
            Toast.makeText(this, "护眼提醒已开启（每 30 分钟）", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "护眼提醒已关闭", Toast.LENGTH_SHORT).show();
        }
        savePrefs();
    }

    /** 弹出模式选择（比轮换更直观，想直接跳到某个模式用这个）。 */
    private void showPageModePicker() {
        final String[] modes = {"上下滚动", "左右翻页", "瞬切翻页"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("阅读模式")
                .setSingleChoiceItems(modes, pageMode, (d, w) -> {
                    pageMode = w;
                    applyPageMode();
                    savePrefs();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用当前阅读模式（不改 pageMode 本身，供外部调用）。 */
    private void applyPageMode() {
        if (pageMode == 0) {
            pageView.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            stopAuto();
            if (!pages.isEmpty() && pages.size() > 1) {
                int pct = (int) (pageIndex * 100f / Math.max(1, pages.size()));
                scroll.post(() -> jumpTo(pct));
            }
        } else {
            scroll.setVisibility(View.GONE);
            pageView.setVisibility(View.VISIBLE);
            paginate();
        }
        chapterBar.setText(chapterLabel());
        String name = pageMode == 0 ? "上下滚动" : (pageMode == 1 ? "左右翻页" : "瞬切翻页");
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
    }

    // ==================== 自动翻页 ====================

    /** 自动翻页开关。开启后按间隔自动前进，到章末自动下一章。 */
    private void toggleAutoPage() {
        if (autoPlaying) {
            stopAuto();
            Toast.makeText(this, "已停止自动翻页", Toast.LENGTH_SHORT).show();
            return;
        }
        autoPlaying = true;
        scheduleAuto();
        Toast.makeText(this, "自动翻页 " + (autoIntervalMs / 1000) + " 秒", Toast.LENGTH_SHORT).show();
    }

    private void scheduleAuto() {
        autoHandler.removeCallbacksAndMessages(null);
        if (!autoPlaying) return;
        autoHandler.postDelayed(this::autoAdvance, autoIntervalMs);
    }

    private void stopAuto() {
        autoPlaying = false;
        autoHandler.removeCallbacksAndMessages(null);
    }

    /** 单次自动前进：翻页模式翻页，滚动模式按屏幕高度滚一屏。 */
    private void autoAdvance() {
        if (isFinishing() || isDestroyed()) {
            stopAuto();
            return;
        }
        if (pageMode == 0) {
            int step = Math.max(1, scroll.getHeight() - dp(60));
            int max = scroll.getChildAt(0) == null ? 0
                    : scroll.getChildAt(0).getHeight() - scroll.getHeight();
            if (scroll.getScrollY() + step >= max) {
                if (hasNextChapter()) switchChapter(1);
                else {
                    stopAuto();
                    Toast.makeText(this, "全书读完", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                scroll.smoothScrollBy(0, step);
            }
        } else {
            int next = pageIndex + 1;
            if (next >= pages.size()) {
                if (hasNextChapter()) {
                    switchChapter(1);
                } else {
                    stopAuto();
                    Toast.makeText(this, "全书读完", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                pageIndex = next;
                showPage();
            }
        }
        scheduleAuto();
    }

    private boolean hasNextChapter() {
        return session.chapters != null && session.chapterIndex < session.chapters.size() - 1;
    }

    /** 调自动翻页速度（秒）。 */
    private void showAutoSpeed() {
        final String[] labels = {"5 秒", "10 秒", "20 秒", "30 秒", "60 秒"};
        final long[] values = {5000, 10000, 20000, 30000, 60000};
        int checked = 2;
        for (int i = 0; i < values.length; i++) if (values[i] == autoIntervalMs) checked = i;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自动翻页间隔")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    autoIntervalMs = values[w];
                    savePrefs();
                    if (autoPlaying) scheduleAuto();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== TTS 朗读 ====================

    /** 朗读开关：拿当前页正文交给系统 TTS。 */
    private void toggleTts() {
        if (speaking) {
            stopTts();
            Toast.makeText(this, "已停止朗读", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = pageMode == 0 ? renderedText
                : (pages.isEmpty() ? renderedText : pages.get(Math.min(pageIndex, pages.size() - 1)));
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "本页无正文", Toast.LENGTH_SHORT).show();
            return;
        }
        startTts(unescape(text));
    }

    private void startTts(String text) {
        try {
            if (tts == null) {
                tts = new android.speech.tts.TextToSpeech(this, status -> {
                    ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
                    if (!ttsReady) {
                        Toast.makeText(this, "语音引擎不可用", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        tts.setLanguage(java.util.Locale.CHINESE);
                        tts.setSpeechRate(1.0f);
                    } catch (Throwable ignored) {
                    }
                });
                // 引擎初始化是异步的，先缓一下再念
                speakDelayed(text);
            } else {
                speakNow(text);
            }
        } catch (Throwable e) {
            Toast.makeText(this, "朗读失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void speakDelayed(String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (ttsReady) speakNow(text);
            else speakDelayed(text);
        }, 300);
    }

    private void speakNow(String text) {
        try {
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "novel_page");
            speaking = true;
            Toast.makeText(this, "正在朗读本页", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "朗读失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopTts() {
        speaking = false;
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    private void releaseTts() {
        stopTts();
        try {
            if (tts != null) {
                tts.shutdown();
                tts = null;
                ttsReady = false;
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 全文搜索 ====================

    /** 在当前章正文里搜关键词，命中则跳到那一页。 */
    private void showSearch() {
        String text = unescape(renderedText);
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "本章无正文", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入关键词");
        input.setSingleLine(true);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("章内搜索")
                .setView(input)
                .setPositiveButton("查找", (d, w) -> doSearch(input.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 执行搜索：在页集里找首个命中页。 */
    private void doSearch(String kw) {
        if (TextUtils.isEmpty(kw)) return;
        if (pages.isEmpty()) {
            Toast.makeText(this, "请先切到翻页模式", Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).contains(kw)) {
                pageIndex = i;
                showPage();
                Toast.makeText(this, "第 " + (i + 1) + " 页命中", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "本章未找到「" + kw + "」", Toast.LENGTH_SHORT).show();
    }

    // ==================== 阅读设置面板 ====================

    /** 背景主题：0 羊皮纸 1 白纸 2 护眼绿 3 夜间 */
    private int themeIndex;
    private int bgColor = 0xFFF5F2E8;
    private int fgColor = 0xFF333333;
    /** 行距倍数 */
    private float lineSpacing = 1f;

    private void showReaderSettings() {
        final String[] themes = {"羊皮纸", "白纸", "护眼绿", "夜间"};
        final int[] bgColors = {0xFFF5F2E8, 0xFFFFFFFF, 0xFFCCE8CF, 0xFF1C1C1C};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView t1 = new TextView(this);
        t1.setText("背景主题");
        t1.setTextSize(13);
        t1.setTextColor(0xFF888888);
        box.addView(t1);

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setPadding(0, dp(8), 0, dp(16));
        for (int i = 0; i < themes.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(themes[i]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, dp(9), 0, dp(9));
            chip.setTextColor(idx == 3 ? 0xFFDDDDDD : 0xFF333333);
            chip.setBackgroundColor(bgColors[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                themeIndex = idx;
                night = idx == 3;
                applyTheme();
                if (!TextUtils.isEmpty(renderedText)) {
                    int keep = progressBar == null ? 0 : progressBar.getProgress();
                    renderText(renderedText);
                    if (keep > 0) scroll.post(() -> jumpTo(keep));
                }
                savePrefs();
            });
            themeRow.addView(chip);
        }
        box.addView(themeRow);

        TextView t2 = new TextView(this);
        t2.setText("行距：" + String.format("%.1f", lineSpacing));
        t2.setTextSize(13);
        t2.setTextColor(0xFF888888);
        box.addView(t2);

        SeekBar ls = new SeekBar(this);
        ls.setMax(20);
        ls.setProgress((int) ((lineSpacing - 0.8f) * 20f));
        ls.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (!fromUser) return;
                lineSpacing = 0.8f + prog / 20f;
                t2.setText("行距：" + String.format("%.1f", lineSpacing));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                savePrefs();
                if (!TextUtils.isEmpty(renderedText)) {
                    int keep = progressBar == null ? 0 : progressBar.getProgress();
                    renderText(renderedText);
                    if (keep > 0) scroll.post(() -> jumpTo(keep));
                }
            }
        });
        box.addView(ls);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("阅读设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    // ==================== 设置持久化 ====================

    private void loadPrefs() {
        try {
            prefs = getSharedPreferences("novel_reader", MODE_PRIVATE);
            fontSize = prefs.getFloat("font_size", 17f);
            night = prefs.getBoolean("night", false);
            pageMode = prefs.getInt("page_mode", 1);
            autoIntervalMs = prefs.getLong("auto_interval", 20000L);
            themeIndex = prefs.getInt("theme", 0);
            lineSpacing = prefs.getFloat("line_spacing", 1f);
            eyeCare = prefs.getBoolean("eye_care", false);
            night = themeIndex == 3;
        } catch (Throwable ignored) {
        }
    }

    private void savePrefs() {
        try {
            if (prefs == null) prefs = getSharedPreferences("novel_reader", MODE_PRIVATE);
            prefs.edit()
                    .putFloat("font_size", fontSize)
                    .putBoolean("night", night)
                    .putInt("page_mode", pageMode)
                    .putLong("auto_interval", autoIntervalMs)
                    .putInt("theme", themeIndex)
                    .putFloat("line_spacing", lineSpacing)
                    .putBoolean("eye_care", eyeCare)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 全屏沉浸：隐藏状态栏 / 导航栏，再点还原。 */
    private void toggleFullscreen() {
        if (isFs) {
            isFs = false;
            com.fongmi.android.tv.utils.Util.showSystemUI(this);
        } else {
            isFs = true;
            com.fongmi.android.tv.utils.Util.hideSystemUI(this);
        }
        if (pagedMode()) paginate();
    }

    /**
     * 把正文切成整页。
     * <p>
     * 做法：用与真实 TextView 完全相同的宽度、字号、行距建一个离屏 Paint，
     * 逐行测量已耗费高度，超过可视高度就断页。这比按字数估算准得多，
     * 中文混排、标点换行都不会算错。
     */
    private void paginate() {
        pages.clear();
        String text = unescape(renderedText);
        if (TextUtils.isEmpty(text)) {
            pageView.setText("");
            return;
        }
        int w = viewW > 0 ? viewW : getResources().getDisplayMetrics().widthPixels;
        int h = viewH > 0 ? viewH : getResources().getDisplayMetrics().heightPixels;
        int padL = dp(22), padR = dp(22), padT = dp(30), padB = dp(70);
        int usableW = Math.max(1, w - padL - padR);
        int usableH = Math.max(1, h - padT - padB);

        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTextSize(fontSize * getResources().getDisplayMetrics().scaledDensity);
        paint.setAntiAlias(true);
        int lineH = (int) (paint.descent() - paint.ascent() + dp(fontSize * 0.55f));
        int maxLines = Math.max(1, usableH / Math.max(1, lineH));

        // 先按段落切成“逻辑行”，再按宽度折行，最后按 maxLines 装页
        java.util.List<String> logicalLines = new java.util.ArrayList<>();
        for (String para : text.split("\n")) {
            String p = para == null ? "" : para.trim();
            if (p.isEmpty()) continue;
            logicalLines.add("\u3000\u3000" + p);
        }

        StringBuilder page = new StringBuilder();
        int linesInPage = 0;
        for (String line : logicalLines) {
            int wrapped = (int) Math.ceil(paint.measureText(line) / (float) usableW);
            wrapped = Math.max(1, wrapped);
            if (linesInPage + wrapped > maxLines && page.length() > 0) {
                pages.add(page.toString());
                page.setLength(0);
                linesInPage = 0;
            }
            if (wrapped > maxLines) {
                // 单段超长（极端情况）：硬切
                int start = 0;
                while (start < line.length()) {
                    int take = Math.min(line.length() - start, usableW);
                    String seg = line.substring(start, start + take);
                    pages.add(seg);
                    start += take;
                }
                linesInPage = 0;
                continue;
            }
            if (page.length() > 0) page.append('\n');
            page.append(line);
            linesInPage += wrapped;
        }
        if (page.length() > 0) pages.add(page.toString());
        if (pages.isEmpty()) pages.add("");

        pageIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        showPage();
    }

    private void showPage() {
        if (pages.isEmpty()) return;
        pageIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        pageView.setText(pages.get(pageIndex));
        progressBar.setProgress((int) ((pageIndex + 1) * 100f / pages.size()));
        progressText.setText((pageIndex + 1) + "/" + pages.size());
    }

    /** 翻页。越过首/末页时自动切章。 */
    private void turnPage(int delta) {
        if (!pagedMode()) return;
        int next = pageIndex + delta;
        if (next < 0) {
            if (session.chapters != null && session.chapterIndex > 0) {
                switchChapter(-1);
                // 跳到新章最后一页
                pageIndex = Math.max(0, pages.size() - 1);
                showPage();
            } else {
                Toast.makeText(this, "已是第一页", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (next >= pages.size()) {
            if (session.chapters != null && session.chapterIndex < session.chapters.size() - 1) {
                switchChapter(1);
            } else {
                Toast.makeText(this, "已是最后一页", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        pageIndex = next;
        showPage();
    }

    /**
     * 统一触摸分发：左右滑翻页，轻点呼出/隐藏上下栏。
     * 返回 true 消费事件，避免 ScrollView 自己再处理。
     */
    private boolean onReaderTouch(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                return true;
            }
            case android.view.MotionEvent.ACTION_UP -> {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;
                boolean isTap = Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12) && dt < 300;
                if (isTap) {
                    togglePanel();
                    return true;
                }
                if (pagedMode()) {
                    if (Math.abs(dx) > dp(50) && Math.abs(dx) > Math.abs(dy)) {
                        turnPage(dx < 0 ? 1 : -1);
                        return true;
                    }
                } else if (Math.abs(dy) > dp(30)) {
                    // 滚动模式：手势交给 ScrollView
                    return false;
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_MOVE -> {
                if (pagedMode()) return true;
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** 点正文空白处收起/展开上下栏（沉浸阅读）。 */
    private void togglePanel() {
        boolean show = topPanel.getVisibility() != View.VISIBLE;
        topPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void onScroll() {
        if (suppressProgress || progressBar == null || scroll == null) return;
        int h = scroll.getChildAt(0) == null ? 0 : scroll.getChildAt(0).getHeight();
        int max = h - scroll.getHeight();
        if (max <= 0) return;
        int p = (int) (scroll.getScrollY() * 100f / max);
        progressBar.setProgress(Math.max(0, Math.min(100, p)));
        progressText.setText(p + "%");
    }

    private void jumpTo(int percent) {
        int h = scroll.getChildAt(0) == null ? 0 : scroll.getChildAt(0).getHeight();
        int max = h - scroll.getHeight();
        if (max <= 0) return;
        scroll.scrollTo(0, (int) (max * percent / 100f));
        progressText.setText(percent + "%");
    }

    private TextView action(String text, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(11), 0, dp(11));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 正文加载 ====================

    private void loadChapter() {
        loading = false;
        titleBar.setText(session.bookTitle);
        chapterBar.setText(chapterLabel());

        // ① novel:// 载荷自带正文（最常见路径，零请求）
        String inline = parseNovelPayload(session.chapterUrl);
        if (!TextUtils.isEmpty(inline)) {
            renderText(inline);
            saveProgress();
            return;
        }

        // ② 判别层已给正文
        String text = session.text == null ? "" : session.text;
        if (!TextUtils.isEmpty(text)) {
            renderText(text);
            saveProgress();
            return;
        }

        // ③ 两种都没有，按章节地址现取
        requestChapterText();
    }

    /**
     * 解析 novel:// 载荷。
     * <p>
     * 约定与影视+ 一致：novel:// 后面跟 JSON {id,title,content,vodPlayUrl}。
     * content 非空直接用；为空但有 vodPlayUrl 时，地址退回二次请求路径。
     *
     * @return 正文；无法解析返回空串
     */
    private String parseNovelPayload(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        if (!u.startsWith(SCHEME_NOVEL)) return "";

        String json = u.substring(SCHEME_NOVEL.length()).trim();
        if (json.isEmpty()) return "";

        try {
            JSONObject o = new JSONObject(json);
            String content = o.optString("content", "");
            String title = o.optString("title", "");

            // 章名优先用载荷里的 title（数据源最准），回写后顶部栏会跟着变
            if (!TextUtils.isEmpty(title)) {
                session.chapterTitleOverride = title;
            }
            if (!TextUtils.isEmpty(content)) return content;

            // content 为空：拿 vodPlayUrl 走二次请求
            String playUrl = o.optString("vodPlayUrl", "");
            String id = o.optString("id", "");
            if (!TextUtils.isEmpty(playUrl)) {
                session.chapterUrl = playUrl;
            } else if (!TextUtils.isEmpty(id)) {
                session.chapterUrl = id;
            }
            return "";
        } catch (Throwable e) {
            // 不是 JSON（有的源直接把正文塞在 novel:// 后面）→ 当纯文本
            return json;
        }
    }

    /** 转义序列还原：把字面量反斜杠n 变成真换行。 */
    private static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf("\\n") < 0 && s.indexOf("\\r") < 0 && s.indexOf("\\u") < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x5C && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n' || n == 'r') { sb.append('\n'); i++; }
                else if (n == 't') { sb.append(' '); i++; }
                else if (n == '"') { sb.append('"'); i++; }
                else if (n == 0x5C) { sb.append((char) 0x5C); i++; }
                else if (n == 'u') {
                    if (i + 5 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                            i += 5;
                        } catch (Throwable ignored) {
                            sb.append(c);
                        }
                    } else {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 渲染正文。转义还原 → 按行切 → 每段缩进两字。 */
    private void renderText(String raw) {
        // 翻页模式下正文由 pageView 承载，滚动容器只是文字来源；
        // 两边都要更新，否则切目录后看不到新章内容（这是切章不生效的成因）。
        pageIndex = 0;
        renderedText = raw;
        if (pagedMode()) {
            paginate();
            return;
        }
        renderScrollText(raw);
    }

    /** 只渲染滚动模式的多段 TextView。 */
    private void renderScrollText(String raw) {
        content.removeAllViews();
        chapterBar.setText(chapterLabel());
        progressBar.setProgress(0);
        progressText.setText("0%");

        String text = unescape(raw);

        ReadingSession.Chapter cur = session.currentChapter();
        String head = cur != null && !TextUtils.isEmpty(cur.name) ? cur.name : "";
        if (!TextUtils.isEmpty(head)) {
            TextView h = new TextView(this);
            h.setText(head);
            h.setTextColor(themeIndex == 3 ? 0xFFDDDDDD : HEAD);
            h.setTextSize(fontSize + 2f);
            h.setGravity(Gravity.CENTER);
            h.setPadding(0, 0, 0, dp(20));
            content.addView(h);
        }

        int added = 0;
        for (String para : text.split("\n")) {
            String p = para == null ? "" : para.trim();
            if (p.isEmpty()) continue;
            TextView tv = new TextView(this);
            // 首行缩进两字：中文排版惯例，视觉上与影视+ 对齐
            tv.setText("\u3000\u3000" + p);
            tv.setTextColor(fgColor);
            tv.setTextSize(fontSize);
            tv.setLineSpacing(dp(fontSize * 0.55f * lineSpacing), 1f);
            tv.setPadding(0, 0, 0, dp(13));
            content.addView(tv);
            added++;
        }

        if (added == 0) {
            TextView tv = new TextView(this);
            tv.setText("本章无正文内容");
            tv.setTextColor(HINT);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(40), 0, 0);
            content.addView(tv);
        }

        scroll.post(() -> {
            scroll.scrollTo(0, 0);
            if (session.startFraction > 0) jumpTo(session.startFraction);
        });
    }

    private void showTip(String tip, boolean retryable) {
        content.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(tip);
        tv.setTextColor(HINT);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(50), 0, dp(18));
        content.addView(tv);
        if (!retryable) return;
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextColor(LINK);
        retry.setTextSize(15);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(0, dp(10), 0, dp(10));
        retry.setOnClickListener(v -> requestChapterText());
        content.addView(retry);
    }

    /** 按当前章节地址二次取正文：playerContent(siteKey, flag, 章节地址)。 */
    private void requestChapterText() {
        ReadingSession.Chapter c = session.currentChapter();
        String url = c == null ? "" : c.url;
        if (TextUtils.isEmpty(url)) {
            showTip("本章没有地址，无法获取正文", false);
            return;
        }
        String key = session.siteKey == null ? "" : session.siteKey;
        String flag = session.playFlag == null ? "" : session.playFlag;
        if (TextUtils.isEmpty(key)) {
            showTip("缺少源标识，无法获取正文", true);
            return;
        }
        if (loading) return;
        loading = true;
        showTip("正在加载正文…", false);

        com.fongmi.android.tv.utils.Task.execute(() -> {
            String body = null;
            String err = null;
            try {
                com.fongmi.android.tv.bean.Result r =
                        com.fongmi.android.tv.api.SiteApi.playerContent(key, flag, url);
                if (r != null && r.getUrl() != null) {
                    String v = r.getUrl().v();
                    if (!TextUtils.isEmpty(v)) body = v;
                }
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final String finalBody = body;
            final String finalErr = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (TextUtils.isEmpty(finalBody)) {
                    showTip("未能获取正文" + (TextUtils.isEmpty(finalErr) ? "" : "：" + finalErr), true);
                    return;
                }
                // 二次结果也可能是 novel:// 载荷，统一过一遍解析
                String norm = parseNovelPayload(finalBody);
                if (TextUtils.isEmpty(norm)) norm = finalBody;
                session.text = norm;
                renderText(norm);
                saveProgress();
            });
        });
    }

    // ==================== 章节 ====================

    private String chapterLabel() {
        int total = session.chapters == null ? 0 : session.chapters.size();
        if (total <= 0) return "";
        ReadingSession.Chapter c = session.currentChapter();
        String name = session.chapterTitleOverride;
        if (TextUtils.isEmpty(name)) {
            name = c == null || TextUtils.isEmpty(c.name) ? ("第" + (session.chapterIndex + 1) + "章") : c.name;
        }
        return name + "  (" + (session.chapterIndex + 1) + "/" + total + ")";
    }

    private void switchChapter(int delta) {
        if (session.chapters == null || session.chapters.isEmpty()) {
            Toast.makeText(this, "无章节列表", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = session.chapterIndex + delta;
        if (target < 0 || target >= session.chapters.size()) {
            Toast.makeText(this, delta > 0 ? "已是最后一章" : "已是第一章", Toast.LENGTH_SHORT).show();
            return;
        }
        openChapter(target);
    }

    /**
     * 目录面板。
     *
     * <p>上一版用 {@code setSingleChoiceItems} 一次性塞进上千条目，
     * 2400 章的源会明显卡顿；这里改成 RecyclerView + 搜索框：
     * 只渲染可见项，且支持按关键词过滤（长书找章必备）。</p>
     */
    private void showChapters() {
        if (session.chapters == null || session.chapters.isEmpty()) {
            Toast.makeText(this, "无章节列表", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));

        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("搜索章节名");
        search.setSingleLine(true);
        search.setTextSize(14);
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView count = new TextView(this);
        count.setTextSize(12);
        count.setTextColor(0xFF888888);
        count.setPadding(0, dp(6), 0, dp(6));
        count.setText("共 " + session.chapters.size() + " 章");
        box.addView(count);

        androidx.recyclerview.widget.RecyclerView rv =
                new androidx.recyclerview.widget.RecyclerView(this);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        box.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        final java.util.List<Integer> shown = new java.util.ArrayList<>();
        final ChapterAdapter adapter = new ChapterAdapter(shown, count);
        rv.setAdapter(adapter);
        adapter.rebuild("");

        // 打开时滚到当前章
        rv.post(() -> {
            int pos = shown.indexOf(session.chapterIndex);
            if (pos > 0) rv.scrollToPosition(pos);
        });

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                adapter.rebuild(e == null ? "" : e.toString().trim());
            }
        });

        final androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("目录")
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .create();

        adapter.onPick = which -> {
            dlg.dismiss();
            openChapter(which);
        };
        dlg.show();
    }

    /** 打开指定章（目录 / 翻页越界共用）。 */
    private void openChapter(int which) {
        if (session.chapters == null || which < 0 || which >= session.chapters.size()) return;
        session.chapterIndex = which;
        session.startFraction = -1;
        session.text = "";
        session.chapterTitleOverride = "";
        session.chapterUrl = session.chapters.get(which).url;
        loadChapter();
    }

    /** 章节目录适配器：只渲染可见项，支持过滤。 */
    private class ChapterAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ChapterAdapter.H> {

        private final java.util.List<Integer> shown;
        private final TextView countView;
        java.util.function.IntConsumer onPick;

        ChapterAdapter(java.util.List<Integer> shown, TextView countView) {
            this.shown = shown;
            this.countView = countView;
        }

        /** 按关键词重建可见列表（空串 = 全部）。 */
        void rebuild(String kw) {
            shown.clear();
            String k = kw == null ? "" : kw;
            for (int i = 0; i < session.chapters.size(); i++) {
                String name = session.chapters.get(i).name;
                if (k.isEmpty() || (name != null && name.contains(k))) shown.add(i);
            }
            if (countView != null) {
                countView.setText(k.isEmpty()
                        ? "共 " + session.chapters.size() + " 章"
                        : "匹配 " + shown.size() + " / " + session.chapters.size() + " 章");
            }
            notifyDataSetChanged();
        }

        @androidx.annotation.NonNull
        @Override
        public H onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(NovelReaderActivity.this);
            tv.setTextSize(15);
            tv.setPadding(dp(8), dp(13), dp(8), dp(13));
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new H(tv);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull H h, int position) {
            int idx = shown.get(position);
            ReadingSession.Chapter c = session.chapters.get(idx);
            String name = c == null || TextUtils.isEmpty(c.name) ? ("第" + (idx + 1) + "章") : c.name;
            boolean cur = idx == session.chapterIndex;
            h.tv.setText(cur ? "▶ " + name : name);
            h.tv.setTextColor(cur ? 0xFF1565C0 : fgColor);
            h.tv.setTypeface(cur ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            h.tv.setOnClickListener(v -> {
                if (onPick != null) onPick.accept(idx);
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class H extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final TextView tv;

            H(TextView tv) {
                super(tv);
                this.tv = tv;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // 音量键翻页：阅读器的标配操作，避免腾出手指点屏幕
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (pagedMode()) turnPage(1);
            else scrollByPage(1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (pagedMode()) turnPage(-1);
            else scrollByPage(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 滚动模式按屏翻一页（音量键用）。 */
    private void scrollByPage(int delta) {
        if (scroll == null) return;
        int step = Math.max(1, scroll.getHeight() - dp(60)) * delta;
        scroll.smoothScrollBy(0, step);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAuto();
        stopTts();
        // 护眼提醒只在真正阅读时计时，切后台就停，避免后台还弹提示
        eyeHandler.removeCallbacks(eyeTick);
        savePrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台恢复护眼计时
        if (eyeCare) {
            eyeHandler.removeCallbacks(eyeTick);
            eyeHandler.postDelayed(eyeTick, 30 * 60 * 1000L);
        }
    }

    @Override
    protected void onDestroy() {
        autoHandler.removeCallbacksAndMessages(null);
        releaseTts();
        super.onDestroy();
    }

    private void saveProgress() {
        try {
            ReadingSession.Chapter c = session.currentChapter();
            int permille = 0;
            if (scroll != null && scroll.getChildAt(0) != null) {
                int max = scroll.getChildAt(0).getHeight() - scroll.getHeight();
                if (max > 0) permille = Math.min(10000, scroll.getScrollY() * 10000 / max);
            }
            com.fongmi.android.tv.read.ReadingStore.Record rec =
                    new com.fongmi.android.tv.read.ReadingStore.Record();
            rec.kind = session.kind == null ? "NOVEL" : session.kind.name();
            rec.sourceName = session.sourceName == null ? "" : session.sourceName;
            rec.bookTitle = session.bookTitle == null ? "" : session.bookTitle;
            rec.coverUrl = session.coverUrl == null ? "" : session.coverUrl;
            rec.chapterIndex = session.chapterIndex;
            rec.chapterName = c == null ? "" : c.name;
            rec.chapterUrl = c == null ? "" : c.url;
            rec.siteKey = session.siteKey == null ? "" : session.siteKey;
            rec.playFlag = session.playFlag == null ? "" : session.playFlag;
            rec.ruleTitle = session.ruleTitle == null ? "" : session.ruleTitle;
            rec.detailId = session.detailId == null ? "" : session.detailId;
            rec.position = permille;
            rec.content = session.text == null ? "" : session.text;
            com.fongmi.android.tv.read.ReadingStore.save(rec);
        } catch (Throwable ignored) {
        }
    }
}
