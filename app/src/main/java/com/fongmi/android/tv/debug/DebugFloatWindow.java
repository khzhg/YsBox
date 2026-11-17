package com.fongmi.android.tv.debug;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 调试悬浮窗。UI 对齐参考版：
 * 蓝色标题栏（调试日志 / 停用 / 滚动）→ 四分类 Tab（控制台·接口·系统·Logcat）
 * → 过滤行（关键字输入 + 清空）→ 白色日志区 → 底部五键（复制全部·展开·文本·清空关闭·关闭）。
 *
 * 窗口仍用 TYPE_APPLICATION_PANEL(1000) 免悬浮窗权限，依附当前 Activity；
 * 收起态不抢焦点，展开态可交互（EditText 需要焦点，否则键盘弹不出）。
 */
public class DebugFloatWindow implements DebugLogBus.Listener {

    /** Tab 名称，顺序与 DebugLogBus 的 TYPE_* 常量一致 */
    private static final String[] TAB_NAMES = {"\u63a7\u5236\u53f0", "\u63a5\u53e3", "\u7cfb\u7edf", "Logcat", "\u5168\u90e8"};
    /** 「全部」Tab 的伪类型号（用 TYPE_COUNT 表示"不限桶"）。 */
    private static final int TYPE_ALL = DebugLogBus.TYPE_COUNT;

    private static final int C_BLUE = 0xFF1565C0;
    private static final int C_TAB_SEL = 0xFF4C8FD1;
    private static final int C_TITLE = 0xFFFFFFFF;
    private static final int C_TAB_UNSEL = 0xD9FFFFFF;
    private static final int C_FILTER_BG = 0xFFF2F4F7;
    private static final int C_LOG_BG = 0xFFFFFFFF;
    private static final int C_LOG_TEXT = 0xFF37474F;
    private static final int C_FOOT_BG = 0xFFF5F6F8;
    private static final int C_ACTION = 0xFF1565C0;
    private static final int C_CLEAR = 0xFFE53935;
    private static final int C_PAUSE_OFF = 0x33FFFFFF;
    private static final int C_SCROLL_ON = 0xFF2E7D32;
    private static final int C_SCROLL_OFF = 0x33FFFFFF;
    /**
     * 面板高度占屏比。
     *
     * <p>与参考版对齐：默认态约占半屏。面板内部有约 130dp 固定头部
     * （标题 / Tab / 过滤 / 底部），配合单行渲染，一屏能看到十几条日志。</p>
     */
    private static final float PANEL_RATIO = 0.64f;
    /**
     * 面板顶部留白占屏比。
     *
     * <p>早前用 dp(28) 紧贴状态栏，观感上"弹窗太高、压着标题栏"。
     * 按屏高比例下移，任何分辨率下位置都稳定（对齐参考版的起始位置）。</p>
     */
    private static final float PANEL_TOP_RATIO = 0.16f;
    /** 面板宽度占屏比：与参考版一致的窄边距。 */
    private static final float PANEL_WIDTH_RATIO = 0.94f;

    private static final DebugFloatWindow INSTANCE = new DebugFloatWindow();

    public static DebugFloatWindow get() {
        return INSTANCE;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] tabViews = new TextView[TAB_NAMES.length];

    private WindowManager windowManager;
    private FrameLayout root;
    private ScrollView scroll;
    private TextView logText;
    private TextView pauseBtn;
    private TextView scrollBtn;
    private TextView expandBtn;
    private EditText filterInput;
    private WindowManager.LayoutParams params;

    private Activity host;
    private boolean paused;
    private boolean autoScroll = true;
    private boolean plainMode;
    /**
     * 文本模式的单行状态。
     *
     * <p>与卡片模式共用「展开全部 / 收起全部」按钮：</p>
     * <ul>
     *   <li>列表模式：展开 = 摊开所有卡片；</li>
     *   <li>文本模式：展开 = 取消截断、恢复完整折行。</li>
     * </ul>
     * <p>此前按钮只作用于卡片列表，文本模式下点了毫无反应——用户反馈的
     * "文本展开没有作用"。</p>
     */
    private boolean textSingleLine = true;
    /** 列表模式：卡片式渲染（每条可展开 + 复制）；false = 纯文本模式。 */
    private boolean listMode = true;
    private androidx.recyclerview.widget.RecyclerView logList;
    private CardAdapter cardAdapter;
    private int type = DebugLogBus.TYPE_CONSOLE;
    private String keyword = "";

    private DebugFloatWindow() {
    }

    public boolean isShowing() {
        return root != null && root.getParent() != null;
    }

    public void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (isShowing() && host == activity) return;
        dismiss();
        host = activity;
        windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;
        root = buildCollapsed(activity);
        params = buildParams(activity);
        try {
            windowManager.addView(root, params);
            DebugLogBus.get().addListener(this);
        } catch (Throwable ignored) {
            root = null;
            windowManager = null;
        }
    }

    public void dismiss() {
        DebugLogBus.get().removeListener(this);
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        scroll = null;
        logText = null;
        pauseBtn = null;
        scrollBtn = null;
        expandBtn = null;
        filterInput = null;
        for (int i = 0; i < tabViews.length; i++) tabViews[i] = null;
        windowManager = null;
        host = null;
    }

    private WindowManager.LayoutParams buildParams(Activity activity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        p.format = PixelFormat.TRANSLUCENT;
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        p.width = dp(56);
        p.height = dp(56);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(12);
        p.y = dp(90);
        try {
            p.token = activity.getWindow().getDecorView().getWindowToken();
        } catch (Throwable ignored) {
        }
        return p;
    }

    private int dp(float v) {
        Context c = host != null ? host.getApplicationContext() : null;
        if (c == null) return (int) v;
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable round(int color, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = round(color, radius);
        d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    /** 收起态：蓝色圆点 */
    @SuppressLint("ClickableViewAccessibility")
    private FrameLayout buildCollapsed(Context context) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(round(C_BLUE, dp(28)));

        TextView label = new TextView(context);
        label.setText("\u8c03\u8bd5");
        label.setTextColor(C_TITLE);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.MONOSPACE);
        box.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        box.setOnClickListener(v -> expand());
        setupDrag(box);
        return box;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDrag(View view) {
        final float[] start = new float[2];
        final int[] origin = new int[2];
        view.setOnTouchListener(new View.OnTouchListener() {
            private boolean moved;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        start[0] = event.getRawX();
                        start[1] = event.getRawY();
                        origin[0] = params.x;
                        origin[1] = params.y;
                        moved = false;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = start[0] - event.getRawX();
                        float dy = event.getRawY() - start[1];
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (!moved) return false;
                        params.x = origin[0] + (int) dx;
                        params.y = origin[1] + (int) dy;
                        try {
                            if (root != null && windowManager != null) windowManager.updateViewLayout(root, params);
                        } catch (Throwable ignored) {
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 展开：构建完整面板 */
    private void expand() {
        if (host == null || windowManager == null) return;
        try {
            windowManager.removeView(root);
        } catch (Throwable ignored) {
        }
        root = buildPanel(host);
        applyPanelParams();
        try {
            windowManager.addView(root, params);
            switchType(type);
            applyViewMode();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 应用面板尺寸。
     *
     * <p>对齐影视+ 的观感：小窗、贴底、不遮全屏，方便边看日志边操作页面。
     * 展开态才铺满（长日志时用）。</p>
     */
    private void applyPanelParams() {
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        // 窗口尺寸固定，展开只影响"单行 / 多行"的渲染，不再改窗口大小。
        //
        // 用户明确要求："展开不放大弹窗"。参考版也是这样——展开只是个
        // 阅读模式开关（一行挤一行 vs 每条独占一行），窗口纹丝不动。
        // 早前 expand 会 setLayout 到 0.9 屏，弹窗突然变形，很突兀。
        params.width = (int) (screenW * PANEL_WIDTH_RATIO);
        params.height = (int) (screenH * PANEL_RATIO);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // 顶部按屏高比例下移：不再紧贴状态栏，观感更居中、不压标题栏。
        params.y = (int) (screenH * PANEL_TOP_RATIO);
        params.x = 0;
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        try {
            if (root != null && windowManager != null) {
                if (root instanceof FrameLayout) root.setBackground(round(0xFF1565C0, dp(10)));
                windowManager.updateViewLayout(root, params);
            }
        } catch (Throwable ignored) {
        }
    }

    private void collapse() {
        if (host == null || windowManager == null) return;
        try {
            windowManager.removeView(root);
        } catch (Throwable ignored) {
        }
        root = buildCollapsed(host);
        params.width = dp(56);
        params.height = dp(56);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(12);
        params.y = dp(90);
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        try {
            windowManager.addView(root, params);
        } catch (Throwable ignored) {
        }
    }

    /** 构建面板：蓝标题栏 + Tab + 过滤 + 日志区 + 底部操作 */
    private FrameLayout buildPanel(Context context) {
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setBackgroundColor(C_BLUE);

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(buildTitleBar(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        col.addView(buildTabRow(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        col.addView(buildFilterRow(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_LOG_BG);
        logText = new TextView(context);
        logText.setTextColor(C_LOG_TEXT);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(6), dp(10), dp(6));
        logText.setTextIsSelectable(true);
        // 文本模式保持正常折行：它是"整块等宽文本"视图，用于长按选中、
        // 整体复制；逐条展开是卡片列表（默认视图）的能力，两者职责不同。
        logText.setHorizontallyScrolling(false);
        scroll.addView(logText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 卡片列表视图（与文本视图二选一显示）
        logList = new androidx.recyclerview.widget.RecyclerView(context);
        logList.setBackgroundColor(C_LOG_BG);
        logList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        cardAdapter = new CardAdapter();
        logList.setAdapter(cardAdapter);
        logList.setVisibility(View.GONE);
        col.addView(logList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(buildFooter(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        return wrapper;
    }

    /**
     * 卡片式日志适配器。
     *
     * <p>每条日志一张可展开的卡片：折叠时一行摘要 + 「复制」按钮，
     * 展开时完整多行文本。对齐影视+ 的列表视图——长日志（整段 JSON）
     * 在纯文本模式下会把面板刷得很难看，卡片模式则可按需展开。</p>
     */
    private class CardAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<CardAdapter.H> {

        private final java.util.List<DebugLogBus.Entry> items = new java.util.ArrayList<>();
        /**
         * 已展开的行号。
         *
         * <p>展开是**逐条**的：点哪一条就展开哪一条，其余保持单行。</p>
         *
         * <p>必须按"行号"记录，不能靠 View 的可见性——RecyclerView 会复用
         * ViewHolder，滚动之后展开态会"跑"到别的条目上。</p>
         */
        private final java.util.Set<Integer> expanded = new java.util.HashSet<>();
        /** 「展开全部」总开关 */
        private boolean expandAll;

        void setAllExpanded(boolean all) {
            expandAll = all;
            expanded.clear();
            notifyDataSetChanged();
        }

        boolean isAllExpanded() {
            return expandAll;
        }

        void submit(java.util.List<DebugLogBus.Entry> data) {
            items.clear();
            items.addAll(data);
            // 数据整体重刷时行号已变，旧展开态失效，必须清掉
            expanded.clear();
            notifyDataSetChanged();
        }

        /** 追加一条（新日志到来时用，避免整表重刷）。 */
        void append(DebugLogBus.Entry e) {
            items.add(e);
            notifyItemInserted(items.size() - 1);
        }

        @androidx.annotation.NonNull
        @Override
        public H onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(host);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(0xFFFFFFFF, dp(6), dp(1), 0xFFE0E0E0));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(dp(8), dp(2), dp(8), dp(2));
            card.setLayoutParams(clp);
            // 行高收紧：对齐参考版"一屏十几条"的密度。
            card.setPadding(dp(10), dp(5), dp(10), dp(5));

            // 头部行：摘要 + 复制
            LinearLayout head = new LinearLayout(host);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView summary = new TextView(host);
            summary.setTextSize(12);
            summary.setTextColor(0xFF37474F);
            summary.setSingleLine(true);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(summary, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView copy = new TextView(host);
            copy.setText("复制");
            copy.setTextSize(11);
            copy.setTextColor(0xFF1565C0);
            copy.setPadding(dp(10), dp(4), dp(4), dp(4));
            head.addView(copy);

            card.addView(head);

            // 展开区：完整内容
            TextView full = new TextView(host);
            full.setTextSize(11);
            full.setTextColor(0xFF546E7A);
            full.setTypeface(Typeface.MONOSPACE);
            full.setVisibility(View.GONE);
            full.setTextIsSelectable(true);
            full.setPadding(0, dp(6), 0, 0);
            card.addView(full);

            return new H(card, summary, full, copy);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull H h, int position) {
            DebugLogBus.Entry e = items.get(position);
            String text = plainMode ? e.message : e.line();
            h.summary.setText(text);
            h.full.setText(text);
            // 默认单行；展开与否只看"这一条"的状态，不影响别的条目
            final boolean show = expandAll || expanded.contains(position);
            h.full.setVisibility(show ? View.VISIBLE : View.GONE);
            h.summary.setSingleLine(!show);
            // 点这一条 → 只展开 / 折叠这一条
            h.itemView.setOnClickListener(v -> {
                if (expandAll) {
                    // 当前是"全部展开"，点单条视为收敛到这一条
                    expandAll = false;
                    expanded.clear();
                    if (expandBtn != null) expandBtn.setText("\u5c55\u5f00\u5168\u90e8");
                }
                if (expanded.contains(position)) expanded.remove(position);
                else expanded.add(position);
                notifyItemChanged(position);
            });
            h.copy.setOnClickListener(v -> {
                try {
                    ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("debug_log", text));
                    android.widget.Toast.makeText(host, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class H extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final TextView summary;
            final TextView full;
            final TextView copy;

            H(View item, TextView summary, TextView full, TextView copy) {
                super(item);
                this.summary = summary;
                this.full = full;
                this.copy = copy;
            }
        }
    }

    /** 标题栏：调试日志 | 停用 | 滚动 */
    private LinearLayout buildTitleBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_BLUE);
        bar.setPadding(dp(12), 0, dp(8), 0);

        TextView title = new TextView(context);
        title.setText("\u8c03\u8bd5\u65e5\u5fd7");
        title.setTextColor(C_TITLE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pauseBtn = pill(context, "\u505c\u7528", C_PAUSE_OFF, C_TITLE);
        pauseBtn.setOnClickListener(v -> {
            paused = !paused;
            pauseBtn.setText(paused ? "\u542f\u7528" : "\u505c\u7528");
            pauseBtn.setBackground(round(paused ? C_CLEAR : C_PAUSE_OFF, dp(6)));
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.setMargins(0, 0, dp(8), 0);
        bar.addView(pauseBtn, p1);

        scrollBtn = pill(context, "\u25cf \u6eda\u52a8", C_SCROLL_ON, C_TITLE);
        scrollBtn.setOnClickListener(v -> {
            autoScroll = !autoScroll;
            scrollBtn.setText(autoScroll ? "\u25cf \u6eda\u52a8" : "\u25cb \u6eda\u52a8");
            scrollBtn.setBackground(round(autoScroll ? C_SCROLL_ON : C_SCROLL_OFF, dp(6)));
            if (autoScroll) scrollToBottom();
        });
        bar.addView(scrollBtn);

        return bar;
    }

    /** Tab 行：控制台 / 接口 / 系统 / Logcat */
    private LinearLayout buildTabRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(C_BLUE);
        row.setPadding(dp(6), 0, dp(6), 0);

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            TextView tab = new TextView(context);
            tab.setText(TAB_NAMES[i]);
            // 5 个 Tab 要挤在一行，字号收一点，短名不折行
            tab.setTextSize(13);
            tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);
            tab.setTextColor(C_TAB_UNSEL);
            tab.setOnClickListener(v -> switchType(index));
            tabViews[i] = tab;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(tab, lp);
        }

        return row;
    }

    /** 过滤行：过滤: [输入关键字] ✕ 清空 */
    private LinearLayout buildFilterRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(C_FILTER_BG);
        row.setPadding(dp(10), 0, dp(10), 0);

        TextView label = new TextView(context);
        label.setText("\u8fc7\u6ee4:");
        label.setTextColor(0xFF616161);
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        filterInput = new EditText(context);
        filterInput.setHint("\u8f93\u5165\u5173\u952e\u5b57");
        filterInput.setHintTextColor(0xFF9E9E9E);
        filterInput.setTextColor(0xFF212121);
        filterInput.setTextSize(13);
        filterInput.setSingleLine(true);
        filterInput.setInputType(InputType.TYPE_CLASS_TEXT);
        filterInput.setBackground(round(0xFFFFFFFF, dp(4), dp(1), 0xFFDDDDDD));
        filterInput.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        inputLp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(filterInput, inputLp);

        filterInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s == null ? "" : s.toString().trim();
                refresh();
            }
        });

        TextView clearIcon = new TextView(context);
        clearIcon.setText("\u2715");
        clearIcon.setTextColor(0xFF9E9E9E);
        clearIcon.setTextSize(14);
        clearIcon.setGravity(Gravity.CENTER);
        clearIcon.setPadding(dp(6), dp(4), dp(6), dp(4));
        clearIcon.setOnClickListener(v -> filterInput.setText(""));
        row.addView(clearIcon);

        TextView clearText = new TextView(context);
        clearText.setText("\u6e05\u7a7a");
        clearText.setTextColor(C_CLEAR);
        clearText.setTextSize(13);
        clearText.setPadding(dp(8), dp(4), dp(4), dp(4));
        clearText.setOnClickListener(v -> {
            DebugLogBus.get().clear();
            refresh();
        });
        row.addView(clearText);

        return row;
    }

    /** 底部：复制全部 / 列表·文本 / 展开 / 清空关闭 / 关闭 */
    private LinearLayout buildFooter(Context context) {
        LinearLayout foot = new LinearLayout(context);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setBackgroundColor(C_FOOT_BG);
        foot.setPadding(dp(6), 0, dp(6), 0);

        foot.addView(action(context, "\u590d\u5236\u5168\u90e8", C_ACTION, v -> copyAll()), actionLp());

        // 列表 / 文本切换：列表是卡片态（可展开 + 单条复制），
        // 文本是原来的整块等宽文本（长按选中、整体复制方便）
        foot.addView(action(context, listMode ? "\u5217\u8868" : "\u6587\u672c", C_ACTION,
                v -> switchViewMode(), tv -> viewModeBtn = tv), actionLp());

        expandBtn = action(context, "\u5c55\u5f00\u5168\u90e8", C_ACTION, v -> {
            // 按当前视图分发：
            // 列表模式 → 摊开 / 收回所有卡片；
            // 文本模式 → 取消截断（完整折行）/ 回到单行截断。
            // 此前只处理了列表模式，文本模式下点这个按钮毫无反应。
            if (listMode) {
                if (cardAdapter == null) return;
                boolean all = !cardAdapter.isAllExpanded();
                cardAdapter.setAllExpanded(all);
                expandBtn.setText(all ? "\u6536\u8d77\u5168\u90e8" : "\u5c55\u5f00\u5168\u90e8");
            } else {
                textSingleLine = !textSingleLine;
                applyTextMode();
                refresh();
                expandBtn.setText(textSingleLine ? "\u5c55\u5f00\u5168\u90e8" : "\u6536\u8d77\u5168\u90e8");
            }
        });
        foot.addView(expandBtn, actionLp());

        foot.addView(action(context, "\u6536\u8d77", C_ACTION, v -> collapse()), actionLp());

        // 「关闭」只是收起成悬浮球，不卸载悬浮窗。
        // 早前这里调 dismiss()，把整个悬浮窗（含小球）都移除，
        // 用户点完就再也找不到调试入口了——只能去设置页重新打开开关。
        foot.addView(action(context, "\u5173\u95ed", C_ACTION, v -> collapse()), actionLp());

        return foot;
    }

    /** 视图模式按钮（切换列表 / 文本时更新文案）。 */
    private TextView viewModeBtn;

    /** 按当前模式显示文本区或卡片列表区（不含数据刷新）。 */
    private void applyViewMode() {
        if (scroll != null) scroll.setVisibility(listMode ? View.GONE : View.VISIBLE);
        if (logList != null) logList.setVisibility(listMode ? View.VISIBLE : View.GONE);
        if (viewModeBtn != null) viewModeBtn.setText(listMode ? "\u5217\u8868" : "\u6587\u672c");
        applyTextMode();
        // 切回列表模式时，按钮文案要回到卡片的状态
        if (listMode && expandBtn != null && cardAdapter != null) {
            expandBtn.setText(cardAdapter.isAllExpanded() ? "\u6536\u8d77\u5168\u90e8" : "\u5c55\u5f00\u5168\u90e8");
        } else if (!listMode && expandBtn != null) {
            expandBtn.setText(textSingleLine ? "\u5c55\u5f00\u5168\u90e8" : "\u6536\u8d77\u5168\u90e8");
        }
    }

    /**
     * 把文本模式的显示状态套到 TextView 上。
     *
     * <p>注意：单行截断**不能**用 {@code setMaxLines(1)} 实现——文本区是把
     * 几百条日志拼成的一大块文本，限 1 行的话只会显示最开头那一条。
     * 逐条截断必须在拼接时就做，见 {@link #oneLine(String)}。</p>
     */
    private void applyTextMode() {
        if (logText == null) return;
        logText.setSingleLine(false);
        logText.setEllipsize(null);
        logText.setMaxLines(Integer.MAX_VALUE);
        // 折行由宽度驱动，这样才能长按选中、整体复制完整内容
        logText.setHorizontallyScrolling(false);
    }

    /**
     * 把一条日志压成一行：只压换行，不再按宽度截断内容。
     *
     * <p>早前这里按"估算的 52 字符宽度"截断，但面板真实可视宽度由字号、
     * 字体、缩放共同决定，估算值必然偏差。结果长行先被截出 {@code …}、又被
     * TextView 真实折行——一行变两行，{@code …} 孤零零挂在行中，反而更难读
     * （用户对比影视+ 反馈「打印不全」的根因）。</p>
     *
     * <p>现在文本模式=完整折行（对齐影视+），单条太长就折到下一行，
     * 内容永不丢失；列表模式每条仍可点开看全文。</p>
     */
    private String oneLine(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** 切换视图并重刷。 */
    private void switchViewMode() {
        listMode = !listMode;
        applyViewMode();
        refresh();
    }

    private LinearLayout.LayoutParams actionLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView action(Context context, String text, int color, View.OnClickListener l) {
        return action(context, text, color, l, null);
    }

    /** 带创建回调的重载：需要后续改文案的按钮用这个拿到引用。 */
    private TextView action(Context context, String text, int color,
                            View.OnClickListener l, java.util.function.Consumer<TextView> onCreate) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(6), 0, dp(6));
        v.setOnClickListener(l);
        if (onCreate != null) onCreate.accept(v);
        return v;
    }

    private TextView pill(Context context, String text, int bg, int fg) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextColor(fg);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setBackground(round(bg, dp(6)));
        return v;
    }

    private void switchType(int t) {
        type = t;
        for (int i = 0; i < tabViews.length; i++) {
            TextView v = tabViews[i];
            if (v == null) continue;
            boolean sel = i == t;
            v.setTextColor(sel ? C_TITLE : C_TAB_UNSEL);
            v.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            v.setBackground(sel ? round(C_TAB_SEL, dp(6)) : null);
        }
        refresh();
    }

    private void refresh() {
        if (logText == null) return;
        // 全部 Tab 汇总四个桶，按时间排序；其他 Tab 只看自己那一类
        List<DebugLogBus.Entry> list;
        if (type == TYPE_ALL) {
            list = new java.util.ArrayList<>();
            for (int i = 0; i < DebugLogBus.TYPE_COUNT; i++) {
                list.addAll(DebugLogBus.get().filtered(i, keyword));
            }
            list.sort((a, b) -> Long.compare(a.seq, b.seq));
        } else {
            list = DebugLogBus.get().filtered(type, keyword);
        }
        // 列表模式：交给卡片适配器；文本模式：拼成整块等宽文本
        if (listMode) {
            if (cardAdapter != null) cardAdapter.submit(list);
            if (logList != null && autoScroll) logList.scrollToPosition(
                    Math.max(0, list.size() - 1));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (DebugLogBus.Entry e : list) {
            String line = plainMode ? e.message : e.line();
            // 文本模式默认单行截断（与列表模式观感一致）；
            // 点「展开全部」后放开，显示完整内容。
            sb.append(textSingleLine ? oneLine(line) : line).append("\n");
        }
        logText.setText(sb.toString());
        if (autoScroll) scrollToBottom();
    }

    private void scrollToBottom() {
        if (scroll == null) return;
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyAll() {
        if (host == null) return;
        StringBuilder sb = new StringBuilder();
        List<DebugLogBus.Entry> list = DebugLogBus.get().filtered(type, keyword);
        for (DebugLogBus.Entry e : list) sb.append(e.line()).append("\n");
        if (sb.length() == 0) return;
        ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("debug_log", sb.toString()));
    }

    @Override
    public void onLogAdded(DebugLogBus.Entry entry) {
        // 「全部」Tab 收所有类型，其他 Tab 只收自己那一类
        if (paused) return;
        if (type != TYPE_ALL && entry.type != type) return;
        if (!entry.matches(keyword)) return;
        // 列表模式：直接往卡片适配器追加一条
        if (listMode) {
            if (cardAdapter == null) return;
            cardAdapter.append(entry);
            if (autoScroll && logList != null) {
                logList.post(() -> {
                    try {
                        logList.scrollToPosition(Math.max(0, cardAdapter.getItemCount() - 1));
                    } catch (Throwable ignored) {
                    }
                });
            }
            return;
        }
        if (logText == null) return;
        String line = plainMode ? entry.message : entry.line();
        // 与 refresh() 保持一致：单行模式下逐条截断。
        // 注意 TextView.append() 返回 void，不能链式拼接。
        logText.append(textSingleLine ? oneLine(line) : line);
        logText.append("\n");
        if (autoScroll) scrollToBottom();
    }

    @Override
    public void onCleared() {
        if (logText != null) logText.setText("");
    }
}
