package com.fongmi.android.tv.ui.action;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
     * Action 对话框执行器。
     *
     * <p>把 {@link ActionSpec} 渲染成真正的交互界面，收集用户输入后通过
     * {@link Callback} 回传给数据源。这是 action 协议的核心：数据源用 JSON 描述 UI，
     * 客户端负责显示并把结果送回。</p>
     *
     * <p>支持的类型：input（单项输入）、multiInput / multiInputX（多项输入）、
     * menu（单选菜单，多选菜单由 selected 标记识别）、edit（多行编辑）、
     * help（帮助键值对）、msgbox（纯消息）。</p>
     *
     * <p><b>配色</b>：浅色 + 深色两套。早前一律用浅色文字色（0xFF666666 等），
     * 在夜间主题下弹窗底仍是深色，文字就糊成一片（用户反馈"字体不是白色"）。
     * 现在按宿主是否夜间切换前景色。</p>
     */
public class ActionDialog {

    /** 是否夜间主题：决定文字 / 分隔线用深色还是浅色。 */
    private final boolean night;
    /** 主文字色 */
    private final int colorText;
    /** 次要文字色 */
    private final int colorHint;
    /** 标题 / 强调色 */
    private final int colorStrong;

    /** 用户操作回调。value 是回传给数据源的串，cancel 表示用户取消。 */
    public interface Callback {
        void onSubmit(String value);

        default void onCancel() {
        }
    }

    private final Activity activity;
    private final ActionSpec spec;
    private final Callback callback;
    private AlertDialog dialog;
    /** 多项输入的输入框，按 id 顺序保存。 */
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    /** 多选菜单的选中态。 */
    private final Map<String, Boolean> multiChecked = new LinkedHashMap<>();

    private ActionDialog(Activity activity, ActionSpec spec, Callback callback) {
        this.activity = activity;
        this.spec = spec;
        this.callback = callback;
        // 颜色一律取自项目的弹窗主题（DialogThemeHelper），不再自己猜深浅。
        //
        // 这套应用有 9 种弹窗风格（白 / iOS / AMOLED / 霓虹 / 雾 / 紫 / 深黑…），
        // 每种风格的语义色都不同。之前用「猜背景亮度」的办法，遇到渐变、
        // 半透明风格的底板就会猜错，出现深灰字压深色底——用户反复反馈的
        // "字体颜色看不清"。dialogTextPrimary/Hint/Accent 是跟着风格走的，
        // 任何风格下都保证可读。
        this.night = !com.fongmi.android.tv.ui.dialog.DialogThemeHelper.isLightStyle();
        colorText = com.fongmi.android.tv.ui.dialog.DialogThemeHelper.primaryText(activity);
        colorHint = com.fongmi.android.tv.ui.dialog.DialogThemeHelper.hintText(activity);
        colorStrong = com.fongmi.android.tv.ui.dialog.DialogThemeHelper.accent(activity);
    }

    /**
     * 读主题背景色的实际亮度判断深浅。
     *
     * <p>比 uiMode 准得多：应用界面有自己的深色风格，跟系统夜间开关无关。
     * 用 BT.601 加权亮度，低于中值即视为深色。</p>
     */
    private static boolean isDarkTheme(Activity activity) {
        try {
            // 直接看窗口根视图的背景色。相比读主题属性（TypedArray），
            // 这条路只依赖 View/Drawable，不会碰到本项目的编译裁剪问题，
            // 而且反映的是"屏幕上真正看到的底色"。
            android.view.View decor = activity.getWindow().getDecorView();
            android.graphics.drawable.Drawable bg = decor.getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int alpha = (c >>> 24) & 0xFF;
                // 全透明背景不算数：说明真正的底色由别处提供
                if (alpha > 0x40) return (0.299 * r + 0.587 * g + 0.114 * b) < 128;
            }
        } catch (Throwable ignored) {
        }
        // 取不到就按深色处理：本应用 UI 是深色的，浅色字更安全
        //（反之会出现深灰字压在深底上，就是用户截图那种"看不清"）。
        return true;
    }

    public static void show(Activity activity, ActionSpec spec, Callback callback) {
        if (activity == null || spec == null) return;
        try {
            new ActionDialog(activity, spec, callback).build();
        } catch (Throwable e) {
            Toast.makeText(activity, "动作界面打开失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 是否是多选菜单（选项里带 selected 标记）。 */
    private boolean isMultiMenu() {
        for (ActionSpec.Item it : spec.option) if (it.selected) return true;
        return false;
    }

    private void build() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        // 内边距收紧：影视+ 的弹窗很紧凑，这里跟着收
        box.setPadding(dp(18), dp(12), dp(18), dp(6));

        if (!TextUtils.isEmpty(spec.msg)) addMessage(box);
        if (!TextUtils.isEmpty(spec.qrcode)) addQrCode(box);
        if (!TextUtils.isEmpty(spec.imageUrl)) addImage(box);

        if (spec.isInput()) addInput(box);
        else if (spec.isMulti()) addMultiInput(box);
        else if (spec.isMenu()) addMenu(box);
        else if (spec.isEdit()) addEdit(box);
        else if (spec.isHelp()) addHelp(box);

        // 用项目统一的弹窗工厂，而不是裸 AlertDialog。
        // 裸 builder 会回退到 Material 默认白底+默认宽度，切换弹窗风格时
        // 这个弹窗毫无反应，也会出现"深色风格里套了个白框"的割裂感。
        AlertDialog.Builder b = new com.fongmi.android.tv.ui.dialog.DialogThemeHelper.Builder(activity);

        // 自定义标题：不用系统标题栏。系统标题在多行内容场景下会留大片空白
        // （用户截图里弹窗顶部那块），自绘标题可精确控制间距。
        if (!TextUtils.isEmpty(spec.title)) {
            TextView title = new TextView(activity);
            title.setText(spec.title);
            title.setTextSize(17);
            title.setTextColor(colorStrong);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setPadding(dp(18), dp(16), dp(18), dp(4));
            box.addView(title, 0);
        }

        // 限高滚动容器：签到这类十几行内容的弹窗不会撑到全屏
        final int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.66f);
        ScrollView sv = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = android.view.View.MeasureSpec.makeMeasureSpec(
                        maxH, android.view.View.MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, limit);
            }
        };
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setView(sv);

        // button：0 无按钮（扫码场景由 initAction 驱动）/ 1 只有确定 / 3 确定+取消
        //
        // 只有下面两种情况才挂"确定"：
        // ① 脚本显式声明了 button（它说了算）；
        // ② 脚本没声明，但类型确实需要一次提交（input / multiInput / edit）。
        //
        // menu 与 help 一律不自动补：菜单项本身就是"点了就执行"，
        // help 只是展示键值对。否则界面上会出现两个确定——用户截图里
        // 左上角那个是脚本自带的文本行，右下角才是按钮，看着像坏了。
        boolean needButton;
        if (spec.hasButton) {
            needButton = spec.button != 0;
        } else {
            needButton = !spec.isMenu();
        }
        if (needButton) b.setPositiveButton("确定", null);
        if (needButton && spec.button == 3) b.setNegativeButton("取消", (d, w) -> {
            if (callback != null) callback.onCancel();
        });

        dialog = b.create();
        dialog.setOnShowListener(d -> {
            android.widget.Button ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setOnClickListener(v -> submit());
            }
            dialog.setCanceledOnTouchOutside(spec.canceledOnTouchOutside);
            applySize();
            // 打开即执行的动作（扫码轮询等）
            if (!TextUtils.isEmpty(spec.initAction) && callback != null) {
                callback.onSubmit(buildInitPayload());
            }
        });
        dialog.show();
    }

    /**
     * 应用弹窗尺寸。
     *
     * <p>关键点：<b>没有显式指定宽高时也必须限高</b>。
     * 签到这类 help 弹窗的 msg 有十几行，不限高会撑到接近全屏，
     * 背景全被遮住（用户反馈的现象）。这里统一按屏高 70% 封顶，
     * 超出部分由外层 ScrollView 滚动。</p>
     */
    private void applySize() {
        try {
            android.view.Window w = dialog.getWindow();
            if (w == null) return;
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            if (spec.width > 0) {
                // 明确指定宽度：照用（不超过屏宽）
                w.setLayout(Math.min(dp(spec.width), screenW),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            } else {
                // 未指定宽度：占屏 88%，不铺满，两侧留出上下文
                w.setLayout((int) (screenW * 0.88f),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (spec.bottom) w.setGravity(Gravity.BOTTOM);
        } catch (Throwable ignored) {
        }
    }

    private void addMessage(LinearLayout box) {
        TextView tv = new TextView(activity);
        tv.setText(spec.msg);
        tv.setTextSize(14);
        tv.setTextColor(colorText);
        tv.setLineSpacing(dp(4), 1f);
        tv.setPadding(dp(2), 0, dp(2), dp(12));
        box.addView(tv);
    }

    private void addQrCode(LinearLayout box) {
        ImageView iv = new ImageView(activity);
        int size = spec.qrcodeSize > 0 ? spec.qrcodeSize : 220;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(14);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            Glide.with(activity).load(spec.qrcode).into(iv);
        } catch (Throwable ignored) {
        }
        box.addView(iv);
    }

    private void addImage(LinearLayout box) {
        ImageView iv = new ImageView(activity);
        int h = spec.imageHeight > 0 ? spec.imageHeight : 200;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h));
        lp.bottomMargin = dp(14);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            Glide.with(activity).load(spec.imageUrl).into(iv);
        } catch (Throwable ignored) {
        }
        if (spec.imageClickCoord) {
            iv.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP && !inputs.isEmpty()) {
                    EditText et = inputs.values().iterator().next();
                    et.setText((int) e.getX() + "," + (int) e.getY());
                }
                return true;
            });
        }
        box.addView(iv);
    }

    private void addInput(LinearLayout box) {
        // 只读：脚本只想要一个"展示 + 确定"，不要输入框。
        // 签到脚本就是这个形态（type:'input', readonly:true, tip:'确定'）。
        // 早前没判断 readonly，渲染出空输入框并把 tip 当占位提示画进去，
        // 界面上就凭空多了一个灰"确定"。
        if (spec.readonly) return;

        EditText et = new EditText(activity);
        et.setSingleLine(false);
        et.setMaxLines(6);
        et.setTextColor(colorText);
        et.setHintTextColor(colorHint);
        et.setHint(TextUtils.isEmpty(spec.tip) ? "请输入" : spec.tip);
        et.setText(spec.value);
        et.setSelection(et.getText().length());
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(TextUtils.isEmpty(spec.id) ? "_one" : spec.id, et);
        addSelectChips(box, spec.selectData, et);
    }

    private void addMultiInput(LinearLayout box) {
        for (ActionSpec.Item it : spec.input) {
            TextView label = new TextView(activity);
            label.setText(TextUtils.isEmpty(it.name) ? it.id : it.name);
            label.setTextSize(13);
            label.setTextColor(colorHint);
            label.setPadding(0, dp(10), 0, dp(4));
            box.addView(label);

            EditText et = new EditText(activity);
            et.setSingleLine(false);
            et.setMaxLines(4);
            et.setTextColor(colorText);
            et.setHintTextColor(colorHint);
            et.setHint(TextUtils.isEmpty(it.tip) ? "请输入" : it.tip);
            et.setText(it.value);
            box.addView(et, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            String key = TextUtils.isEmpty(it.id) ? it.name : it.id;
            inputs.put(key, et);
            addSelectChips(box, it.selectData, et);

            if (!TextUtils.isEmpty(it.help)) {
                TextView help = new TextView(activity);
                help.setText(it.help);
                help.setTextSize(12);
                help.setTextColor(colorHint);
                box.addView(help);
            }
        }
    }

    /** 建议值区：点击直接填入输入框。 */
    private void addSelectChips(LinearLayout box, String selectData, EditText target) {
        if (TextUtils.isEmpty(selectData)) return;
        // [folder] / [calendar] 这类是系统选择器标记，直接忽略（不阻断输入）
        if (selectData.startsWith("[")) return;
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(0, dp(6), 0, 0);
        for (String pair : selectData.split(",")) {
            String p = pair.trim();
            if (p.isEmpty()) continue;
            int idx = p.indexOf(":=");
            String label = idx > 0 ? p.substring(0, idx) : p;
            String val = idx > 0 ? p.substring(idx + 2) : p;
            TextView chip = new TextView(activity);
            chip.setText(label);
            chip.setTextSize(13);
            chip.setTextColor(colorStrong);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(v -> {
                target.setText(val);
                target.setSelection(target.getText().length());
            });
            wrap.addView(chip);
        }
        box.addView(wrap);
    }

    private void addMenu(LinearLayout box) {
        boolean multi = isMultiMenu();
        for (ActionSpec.Item it : spec.option) {
            TextView row = new TextView(activity);
            row.setText(it.name);
            row.setTextSize(15);
            row.setTextColor(colorText);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            if (multi) {
                multiChecked.put(it.action, it.selected);
                row.setText((it.selected ? "☑ " : "☐ ") + it.name);
            }
            row.setOnClickListener(v -> {
                if (multi) {
                    boolean now = !Boolean.TRUE.equals(multiChecked.get(it.action));
                    multiChecked.put(it.action, now);
                    row.setText((now ? "☑ " : "☐ ") + it.name);
                } else {
                    dismiss();
                    if (callback != null) callback.onSubmit(it.action);
                }
            });
            box.addView(row);
        }
    }

    private void addEdit(LinearLayout box) {
        EditText et = new EditText(activity);
        et.setSingleLine(false);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setText(spec.text);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        inputs.put(TextUtils.isEmpty(spec.id) ? "_edit" : spec.id, et);
    }

    private void addHelp(LinearLayout box) {
        for (ActionSpec.Item it : spec.data) {
            // 跳过纯按钮词：有些脚本会把"确定"当最后一条数据塞进 data，
            // 客户端又会挂真正的按钮，界面上就出现两个确定。
            // 这条纯属视觉噪音，直接不渲染。
            if (isButtonWord(it.name) && TextUtils.isEmpty(it.value)) continue;
            if (isButtonWord(it.value) && TextUtils.isEmpty(it.name)) continue;

            TextView h = new TextView(activity);
            h.setText(it.name);
            h.setTextSize(15);
            h.setTextColor(colorStrong);
            h.setPadding(0, dp(10), 0, dp(4));
            box.addView(h);
            TextView c = new TextView(activity);
            c.setText(it.value);
            c.setTextSize(14);
            c.setTextColor(colorText);
            box.addView(c);
        }
    }

    /** 是否只是按钮文字（确定 / 取消 / 关闭 / 完成）。 */
    private static boolean isButtonWord(String s) {
        if (TextUtils.isEmpty(s)) return false;
        String v = s.trim();
        return "确定".equals(v) || "取消".equals(v) || "关闭".equals(v) || "完成".equals(v) || "好的".equals(v);
    }

    private String buildInitPayload() {
        return spec.initValue;
    }

    private void submit() {
        String value = collect();
        if (spec.keep) {
            Toast.makeText(activity, "已提交", Toast.LENGTH_SHORT).show();
        } else {
            dismiss();
        }
        if (callback != null) callback.onSubmit(value);
    }

    /** 把界面上的输入组装成回传串。 */
    private String collect() {
        if (spec.isMenu()) {
            List<String> picked = new ArrayList<>();
            for (ActionSpec.Item it : spec.option) {
                if (Boolean.TRUE.equals(multiChecked.get(it.action))) picked.add(it.action);
            }
            if (!picked.isEmpty()) return TextUtils.join(",", picked);
            return "";
        }
        // 多项输入：拼成 {id:value,...}
        if (inputs.size() > 1) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                    o.put(e.getKey(), e.getValue().getText().toString());
                }
            } catch (Throwable ignored) {
            }
            return o.toString();
        }
        // 单项输入：带 id 的形态回传 {id:value}（脚本里按 JSON.parse 取字段）
        if (inputs.size() == 1) {
            Map.Entry<String, EditText> e = inputs.entrySet().iterator().next();
            String text = e.getValue().getText().toString();
            if (!TextUtils.isEmpty(spec.id) && !"_one".equals(e.getKey())) {
                org.json.JSONObject o = new org.json.JSONObject();
                try {
                    o.put(spec.id, text);
                    if (!TextUtils.isEmpty(spec.actionId) && !o.has("actionId")) o.put("actionId", spec.actionId);
                } catch (Throwable ignored) {
                }
                return o.toString();
            }
            return text;
        }
        return "";
    }

    public void dismiss() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 判断一个 type 是否需要弹窗（false 表示应交给内建指令处理）。 */
    public static boolean needsDialog(String type) {
        if (type == null) return false;
        switch (type) {
            case "input":
            case "multiInput":
            case "multiInputX":
            case "menu":
            case "edit":
            case "help":
            case "msgbox":
            case "select":
                return true;
            default:
                return false;
        }
    }
}