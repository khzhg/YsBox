package com.fongmi.android.tv.ui.action;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Action 配置模型。
 *
 * <p>数据源可以在列表里放 vod_tag=action 的条目，条目 id 是一段 JSON，
 * 客户端据此弹出输入框 / 多项输入 / 菜单 / 网页等 UI，收集到用户输入后再回传给
 * 数据源的 action 函数。本模型覆盖脚本里出现过的全部字段。</p>
 *
 * <p>对应脚本写法：</p>
 * <pre>
 * {
 *   vod_id: JSON.stringify({actionId:'玩偶域名', id:'domain', type:'input',
 *       title:'玩偶域名', tip:'请输入', value:'', msg:'提示',
 *       selectData:'1:=a,2:=b'}),
 *   vod_name: '玩偶域名',
 *   vod_tag: 'action'
 * }
 * </pre>
 */
public class ActionSpec {

    // ---- 基本 ----
    /** 回传给 action 函数的动作名（必填）。 */
    public String actionId = "";
    /** 单项输入时的字段名，回传时作为值的一部分。 */
    public String id = "";
    /** UI 类型：input / multiInput / multiInputX / menu / edit / msgbox / help / select / webview。 */
    public String type = "input";
    /**
     * JSON 里显式声明的 type（无则不填）。
     *
     * <p>为什么要单独留一份：{@link #type} 带默认值 "input" 是为了方便渲染，
     * 但判断"这段 JSON 到底是不是界面"时不能用它——否则
     * {@code {"msg":"今日已签到"}} 这种纯提示也会被当成界面而多弹一个空框。</p>
     */
    public String rawType = "";
    public String title = "";
    public String tip = "";
    public String value = "";
    public String msg = "";

    // ---- 尺寸与交互 ----
    public int width = 0;
    public int height = 0;
    /** 底部按钮个数：0 无 / 1 确定 / 3 确定+取消。 */
    public int button = 1;
    /**
     * 脚本是否**显式**声明了 button 字段。
     *
     * <p>{@link #button} 默认 1 只是为了方便渲染，但用它来判断"要不要挂按钮"
     * 会出问题：脚本返回 {@code {"type":"help","msg":"...确定"}} 时，
     * 客户端又自动补了一个"确定"，界面上就出现两个确定。</p>
     *
     * <p>所以判定必须以"脚本有没有明说"为准：没声明时只给真正需要
     * 提交动作的类型（input / multiInput / edit）补按钮。</p>
     */
    public boolean hasButton;
    /** 点确定后不关闭，用于连续对话。 */
    public boolean keep;
    /** 倒计时秒数（0 表示不倒数）。 */
    public int timeout = 0;
    public boolean canceledOnTouchOutside = true;
    public float dimAmount = 0f;
    /** 底部弹出（1）还是居中弹出（0）。 */
    public boolean bottom;

    // ---- 扫码 ----
    public String qrcode = "";
    public int qrcodeSize = 0;
    /** 弹窗就执行的动作（如轮询扫码结果）。 */
    public String initAction = "";
    public String initValue = "";
    /** 取消时执行的动作。 */
    public String cancelAction = "";
    public String cancelValue = "";

    // ---- 单项输入辅助 ----
    /** 可选建议值，格式 "1:=a,2:=b"，选择后填入输入框。 */
    public String selectData = "";
    public String imageUrl = "";
    public int imageHeight = 0;
    public boolean imageClickCoord;

    // ---- 多与列表 ----
    public final List<Item> input = new ArrayList<>();
    public final List<Item> option = new ArrayList<>();
    /** help 类型的内容键值对。 */
    public final List<Item> data = new ArrayList<>();
    /** 多行编辑的初始文本。 */
    public String text = "";
    /**
     * 只读展示。
     *
     * <p>签到这类脚本返回 {@code {type:'input', id:'result_box', readonly:true,
     * tip:'确定', msg:'...'}}：它想表达的是"把 msg 展示出来，给个确定就行"，
     * 而不是真让人输入。</p>
     *
     * <p>早前忽略了这个字段，于是渲染出一个空 EditText，把 tip（"确定"）
     * 当占位提示画了进去——用户看到左上角有个灰扑扑的"确定"，
     * 右下角又有一个真按钮，看着就像弹了两个确定。</p>
     */
    public boolean readonly;
    /** 链接型：源内搜索 / 文件夹 / 详情等。 */
    public String folder = "";
    public String content = "";
    public String toast = "";
    /** webview 类型的目标网址（脚本里字段名就是 url）。 */
    public String url = "";
    /** webview 的文字缩放百分比。 */
    public int textZoom = 0;

    /** 一项（输入项 / 菜单项）。 */
    public static class Item {
        public String id = "";
        public String name = "";
        public String tip = "";
        public String value = "";
        public String action = "";
        public boolean selected;
        /** 建议值（[folder] 目录 / [calendar] 日期 / 1:=a,2:=b）。 */
        public String selectData = "";
        public String help = "";
        public String validation = "";
        public int inputType = 0;
    }

    /** 解析条目 id。解析失败返回 null，调用方应退回普通点击行为。 */
    public static ActionSpec parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String s = json.trim();
        if (!s.startsWith("{")) return null;
        try {
            return of(new JSONObject(s));
        } catch (Throwable e) {
            return null;
        }
    }

    public static ActionSpec of(JSONObject o) {
        ActionSpec a = new ActionSpec();
        a.actionId = str(o, "actionId", a.actionId);
        a.id = str(o, "id", a.id);
        a.type = str(o, "type", a.type);
        a.rawType = o.has("type") ? a.type : "";
        a.title = str(o, "title", "");
        a.tip = str(o, "tip", "");
        a.value = str(o, "value", "");
        a.msg = str(o, "msg", "");
        a.width = o.optInt("width", 0);
        a.height = o.optInt("height", 0);
        a.button = o.optInt("button", 1);
        a.hasButton = o.has("button");
        a.readonly = o.optBoolean("readonly", false);
        a.keep = o.optBoolean("keep", false);
        a.timeout = o.optInt("timeout", 0);
        a.canceledOnTouchOutside = o.optBoolean("canceledOnTouchOutside", true);
        a.dimAmount = (float) o.optDouble("dimAmount", 0);
        a.bottom = o.optInt("bottom", 0) == 1;
        a.qrcode = str(o, "qrcode", "");
        a.qrcodeSize = o.optInt("qrcodeSize", 0);
        a.initAction = str(o, "initAction", "");
        a.initValue = str(o, "initValue", "");
        a.cancelAction = str(o, "cancelAction", "");
        a.cancelValue = str(o, "cancelValue", "");
        a.selectData = str(o, "selectData", "");
        a.imageUrl = str(o, "imageUrl", "");
        a.imageHeight = o.optInt("imageHeight", 0);
        a.imageClickCoord = o.optBoolean("imageClickCoord", false);
        a.text = str(o, "text", "");
        a.folder = str(o, "folder", "");
        a.content = str(o, "content", "");
        a.toast = str(o, "toast", "");
        a.url = str(o, "url", "");
        a.textZoom = o.optInt("textZoom", 0);
        fillItems(a.input, o.optJSONArray("input"));
        fillItems(a.option, o.optJSONArray("option"));
        fillData(a.data, o.optJSONObject("data"));
        return a;
    }

    private static void fillItems(List<Item> out, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object raw = arr.opt(i);
            if (raw instanceof JSONObject jo) {
                Item it = new Item();
                it.id = str(jo, "id", "");
                it.name = str(jo, "name", "");
                it.tip = str(jo, "tip", "");
                it.value = str(jo, "value", "");
                it.action = str(jo, "action", "");
                it.selected = jo.optBoolean("selected", false);
                it.selectData = str(jo, "selectData", "");
                it.help = str(jo, "help", "");
                it.validation = str(jo, "validation", "");
                it.inputType = jo.optInt("inputType", 0);
                out.add(it);
            } else if (raw != null) {
                // 简写形式 "菜单3$menu3"
                String s = String.valueOf(raw);
                Item it = new Item();
                int p = s.indexOf('$');
                if (p > 0) {
                    it.name = s.substring(0, p);
                    it.action = s.substring(p + 1);
                } else {
                    it.name = s;
                    it.action = s;
                }
                out.add(it);
            }
        }
    }

    private static void fillData(List<Item> out, JSONObject obj) {
        if (obj == null) return;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Item it = new Item();
            it.name = k;
            it.value = obj.optString(k, "");
            out.add(it);
        }
    }

    private static String str(JSONObject o, String key, String def) {
        String v = o.optString(key, def);
        return v == null ? def : v;
    }

    public boolean isInput() {
        return "input".equals(type);
    }

    public boolean isMulti() {
        return "multiInput".equals(type) || "multiInputX".equals(type);
    }

    public boolean isMenu() {
        return "menu".equals(type);
    }

    public boolean isWebView() {
        return "webview".equals(type);
    }

    public boolean isHelp() {
        return "help".equals(type);
    }

    public boolean isMsgBox() {
        return "msgbox".equals(type);
    }

    public boolean isEdit() {
        return "edit".equals(type);
    }

    @Override
    public String toString() {
        return "ActionSpec{" + type + "," + actionId + "}";
    }
}
