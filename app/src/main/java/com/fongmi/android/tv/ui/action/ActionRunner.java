package com.fongmi.android.tv.ui.action;

import android.app.Activity;
import android.text.TextUtils;
import android.widget.Toast;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONObject;

/**
 * Action 调度中心。
 *
 * <p>完整流水线：</p>
 * <ol>
 *   <li>列表条目是 {@code vod_tag=action} 时，把条目 id 当 JSON 解析成 {@link ActionSpec}；</li>
 *   <li>按 type 弹出对应 UI（{@link ActionDialog}）；</li>
 *   <li>用户提交后，把值回传给数据源的 action 函数；</li>
 *   <li>数据源返回的原文若又是界面描述（如扫码超时转输入框），继续弹窗，形成链式；
 *       若含 msg / toast 则提示；</li>
 *   <li>识别内建指令（__copy__ / __self_search__ / __detail__ 等）并执行本地动作。</li>
 * </ol>
 *
 * <p><b>关键点</b>：整条链用 {@link SiteApi#actionRaw} 拿原始字符串。
 * 若走 {@code Result.fromJson}，没有 url/msg 字段的界面描述会被丢掉，
 * 客户端就只剩一句“动作完成”可弹——这正是之前 action 不弹窗的原因。</p>
 */
public class ActionRunner {

    /** 内建指令。 */
    private static final String BUILTIN_COPY = "__copy__";
    private static final String BUILTIN_SEARCH = "__self_search__";
    private static final String BUILTIN_DETAIL = "__detail__";
    private static final String BUILTIN_TOAST = "__toast__";
    private static final String BUILTIN_REFRESH = "__refresh__";
    private static final String BUILTIN_MSG = "__msg__";
    private static final String BUILTIN_CLOSE = "__close__";

    /**
     * 「关闭弹窗」类指令。
     *
     * <p>各源命名不统一：影视+ 系用 {@code __close__}，签到类源用
     * {@code close_result}（带 {@code result_box} 字段）。这些是**客户端指令**，
     * 意思是"把当前弹窗收掉"，绝不能原样回传给脚本——脚本没有对应分支，
     * 会走兜底并返回「未知: close_result${...}」这种乱码给用户看到。</p>
     */
    private static final String[] CLOSE_DIRECTIVES = {
            "__close__", "close_result", "close", "close_dialog", "dismiss"
    };

    /** 防死循环：同一条链最多回传这么多轮。 */
    private static final int MAX_CHAIN = 20;

    private final Activity activity;
    private final String siteKey;
    /** 上层页面提供的动作出口。 */
    private Runnable refreshListener;
    private java.util.function.Consumer<String> searchListener;
    private java.util.function.Consumer<String> detailListener;

    public ActionRunner(Activity activity, String siteKey) {
        this.activity = activity;
        this.siteKey = siteKey == null ? "" : siteKey;
    }

    public ActionRunner onRefresh(Runnable r) {
        this.refreshListener = r;
        return this;
    }

    public ActionRunner onSearch(java.util.function.Consumer<String> c) {
        this.searchListener = c;
        return this;
    }

    public ActionRunner onDetail(java.util.function.Consumer<String> c) {
        this.detailListener = c;
        return this;
    }

    /** 处理一个 action 条目。 */
    public void handle(Vod item) {
        if (item == null) return;
        String rawId = item.getId();
        if (TextUtils.isEmpty(rawId)) return;

        ActionSpec spec = ActionSpec.parse(rawId);
        if (spec != null) {
            showDialog(spec, 0);
            return;
        }
        // 纯字符串动作名：直接回传数据源
        runChain(rawId, null, 0);
    }

    private void showDialog(final ActionSpec spec, final int round) {
        ActionDialog.show(activity, spec, new ActionDialog.Callback() {
            @Override
            public void onSubmit(String value) {
                if (!TextUtils.isEmpty(spec.actionId)) {
                    runChain(spec.actionId, value, round);
                } else if (!TextUtils.isEmpty(spec.toast)) {
                    Notify.show(spec.toast);
                }
            }

            @Override
            public void onCancel() {
                if (!TextUtils.isEmpty(spec.cancelAction)) {
                    runChain(spec.cancelAction, spec.cancelValue, round);
                }
            }
        });
    }

    /** 回传数据源并处理返回值。 */
    public void runChain(final String action, final String value, final int round) {
        if (TextUtils.isEmpty(action)) return;
        // 客户端指令（关闭弹窗等）就地执行，绝不回传给脚本——
        // 脚本没有对应分支，会走兜底返回「未知: xxx」给用户看到。
        if (isCloseDirective(action)) return;
        if (round > MAX_CHAIN) {
            Notify.show("动作嵌套过深，已停止");
            return;
        }
        final String call = buildCall(action, value);
        Task.execute(() -> {
            String raw;
            try {
                raw = SiteApi.actionRaw(siteKey, call);
            } catch (Throwable e) {
                raw = null;
                final String err = e.getMessage();
                activity.runOnUiThread(() -> Notify.show(
                        err == null ? "动作执行失败" : "动作执行失败：" + err));
                return;
            }
            final String out = raw == null ? "" : raw;
            activity.runOnUiThread(() -> dispatch(out, round));
        });
    }

    /** 组装真正发给数据源的串：有值 → 动作名$值；无值 → 动作名。 */
    private String buildCall(String action, String value) {
        if (TextUtils.isEmpty(value)) return action;
        return action + "$" + value.trim();
    }

    /** 是否「关闭弹窗」类客户端指令（不该回传给脚本）。 */
    private boolean isCloseDirective(String action) {
        if (TextUtils.isEmpty(action)) return false;
        String a = action.trim().toLowerCase(java.util.Locale.ROOT);
        for (String d : CLOSE_DIRECTIVES) if (d.equals(a)) return true;
        return false;
    }

    /** 处理数据源返回的原文。 */
    private void dispatch(String raw, int round) {
        try {
            if (TextUtils.isEmpty(raw)) {
                Notify.show("动作完成");
                return;
            }
            String text = raw.trim();

            // ⓪ 形如 "动作名$JSON" 的返回（脚本把收到的原样回抛，或主动构造）。
            //    早前直接当文本弹，用户只看到一串"未知: xxx${...}"。
            //    这里先按 '$' 拆，尾段若是 JSON 就走 JSON 分支。
            String payload = text;
            int dollar = text.indexOf('$');
            if (dollar > 0 && dollar < text.length() - 1) {
                String tail = text.substring(dollar + 1).trim();
                if (tail.startsWith("{")) payload = tail;
            }

            // 非 JSON：当提示文本直接展示（脚本里 return '回音：xx' 这类）
            if (!payload.startsWith("{")) {
                Notify.show(payload);
                return;
            }

            JSONObject o = new JSONObject(payload);

            // 外层提示：{"action":{...}, "toast":"签到成功，获得5积分"}
            //
            // 关键：脚本把 toast 放在 action 的**兄弟**位置，而界面分支
            // 一进去就 return，这个 toast 会被整个跳过——用户反馈的
            // "签到成功应该返回 toast" 就是这个原因。所以先取出来。
            String outerToast = o.optString("toast", "");
            if (TextUtils.isEmpty(outerToast)) outerToast = o.optString("toast1", "");

            // ① 界面描述：可能是 {"action":{...}} 包装，也可能顶层就是
            JSONObject actionObj = o.optJSONObject("action");
            ActionSpec spec = actionObj != null ? ActionSpec.of(actionObj) : ActionSpec.of(o);
            if (isUiSpec(spec)) {
                // 有外层 toast 就先弹：既展示了界面，用户也能立刻看到结果。
                // 签到这类"弹详情框 + 报一句成功"的形态正需要两者并存。
                if (!TextUtils.isEmpty(outerToast)) Notify.show(outerToast);

                // webview 类型直接开内嵌页，不弹对话框
                if (spec.isWebView()) {
                    String url = !TextUtils.isEmpty(spec.url) ? spec.url
                            : (!TextUtils.isEmpty(spec.content) ? spec.content
                            : (!TextUtils.isEmpty(spec.qrcode) ? spec.qrcode : spec.msg));
                    ActionWebActivity.start(activity, url,
                            TextUtils.isEmpty(spec.title) ? "网页" : spec.title, spec.textZoom);
                    return;
                }
                if (runBuiltin(spec, round)) return;
                showDialog(spec, round + 1);
                return;
            }

            // ② 纯提示：{"toast":"xx"} / {"msg":"xx"}
            String toast = o.optString("toast", "");
            if (TextUtils.isEmpty(toast)) toast = o.optString("msg", "");
            if (!TextUtils.isEmpty(toast)) {
                Notify.show(toast);
                return;
            }
            String toast1 = o.optString("toast1", "");
            if (!TextUtils.isEmpty(toast1)) {
                Notify.show(toast1);
                return;
            }

            // ②.5 只有 actionId 的指令（多为关闭类）：收尾但别把提示一起吞了。
            //      签到这类脚本会把结果文案放在 msg / toast 里，用户需要看到
            //      "签到成功，积分 +5" 这样的反馈，不能静默。
            String onlyId = o.optString("actionId", "");
            if (isCloseDirective(onlyId)) {
                String tip = o.optString("msg", "");
                if (TextUtils.isEmpty(tip)) tip = o.optString("toast", "");
                if (TextUtils.isEmpty(tip)) tip = o.optString("content", "");
                // result_box：脚本把结果文案放在这里（签到脚本就用了这个字段名）。
                // 早前漏了它，用户点完签到只看到弹窗消失、什么都没提示。
                if (TextUtils.isEmpty(tip)) tip = o.optString("result_box", "");
                if (!TextUtils.isEmpty(tip)) Notify.show(tip);
                return;
            }

            // ③ 内建指令形态
            if (actionObj != null && runBuiltin(spec, round)) return;

            // ③.5 接口回执形态：{"code":0,"msg":""} / {"success":true} 这类
            //
            //   这不是给用户看的文案，是脚本把底层接口的原始回执直接 return 了出来。
            //   早前会掉进下面的 ④ 兜底分支，把整段 JSON 原样弹成 toast——
            //   用户截图里的 `{"code":0,"msg":""}` 就是这么冒出来的。
            //
            //   判据：只含协议字段（code/msg/success/data/message），且没有任何
            //   待渲染的界面描述。msg 非空时按提示弹，为空则视为"静默成功"。
            if (isProtocolEcho(o)) {
                String note = o.optString("msg", "");
                if (TextUtils.isEmpty(note)) note = o.optString("message", "");
                if (!TextUtils.isEmpty(note)) Notify.show(note);
                return;
            }

            // ④ 认不出来：原样展示，方便排查
            Notify.show(text.length() > 200 ? text.substring(0, 200) : text);
        } catch (Throwable e) {
            Notify.show("动作处理失败：" + e.getMessage());
        }
    }

    /**
     * 是否是"接口回执"而不是"用户可见文案"。
     *
     * <p>脚本经常写成 {@code return JSON.stringify(resp)}，把接口的
     * {@code {"code":0,"msg":""}} 原样抛给客户端。这类 JSON 没有任何
     * 界面描述字段，也不该被当提示弹出来。</p>
     *
     * <p>只认协议字段：只要出现了 type / actionId / url / content 等
     * 界面相关键，就不算回执，交给后面的分支正常处理。</p>
     */
    private boolean isProtocolEcho(JSONObject o) {
        // 有界面描述键 → 不是回执
        if (o.has("action") || o.has("type") || o.has("actionId") || o.has("url")
                || o.has("content") || o.has("qrcode") || o.has("items")) return false;
        // 有 toast / result_box 等显式文案键 → 交给前面的分支
        if (o.has("toast") || o.has("toast1") || o.has("result_box")) return false;
        // 必须含至少一个协议字段，避免把 {} 之外的普通 JSON 也吞掉
        return o.has("code") || o.has("success") || o.has("status")
                || o.has("data") || o.has("message");
    }

    /**
     * 判断返回的描述是否真的需要渲染成界面。
     *
     * <p><b>只认显式声明的 type</b>，不能看 actionId。</p>
     *
     * <p>踩过的坑：签到脚本返回 {@code {"result_box":"","actionId":"close_result"}}，
     * 本意是"关掉结果框"（客户端指令），但早前把"actionId 非空"也算界面，
     * 于是多弹了一个空对话框；用户点确定后客户端又把 {@code close_result}
     * 回传给脚本，脚本没有对应分支，返回「未知: close_result${...}}」——
     * 这就是用户看到的"弹出未知"。</p>
     */
    private boolean isUiSpec(ActionSpec spec) {
        if (spec == null) return false;
        return !TextUtils.isEmpty(spec.rawType);
    }

    private boolean runBuiltin(ActionSpec spec, int round) {
        String id = spec.actionId;
        if (TextUtils.isEmpty(id) || !id.startsWith("__")) return false;
        return runBuiltinById(id, spec);
    }

    private boolean runBuiltinById(String id, ActionSpec spec) {
        if (BUILTIN_COPY.equals(id)) {
            copyToClipboard(spec.content);
            return true;
        }
        if (BUILTIN_TOAST.equals(id) || BUILTIN_MSG.equals(id)) {
            Notify.show(TextUtils.isEmpty(spec.msg) ? spec.content : spec.msg);
            return true;
        }
        if (BUILTIN_REFRESH.equals(id)) {
            if (refreshListener != null) refreshListener.run();
            Notify.show("已刷新");
            return true;
        }
        if (BUILTIN_CLOSE.equals(id)) {
            return true;
        }
        if (BUILTIN_SEARCH.equals(id)) {
            if (searchListener != null) {
                String kw = extractSearchKeyword(spec);
                if (!TextUtils.isEmpty(kw)) {
                    searchListener.accept(kw);
                    return true;
                }
            }
            Notify.show("源内搜索：缺少关键词");
            return true;
        }
        if (BUILTIN_DETAIL.equals(id)) {
            if (detailListener != null && !TextUtils.isEmpty(spec.id)) {
                detailListener.accept(spec.id);
                return true;
            }
            Notify.show("无法打开详情：缺少 id");
            return true;
        }
        return false;
    }

    /** 从 folder 描述里抽出搜索词。folder 形如 "查询1$词$0-0-S#查询2$词$3"。 */
    private String extractSearchKeyword(ActionSpec spec) {
        String src = !TextUtils.isEmpty(spec.folder) ? spec.folder
                : (!TextUtils.isEmpty(spec.content) ? spec.content : spec.msg);
        if (TextUtils.isEmpty(src)) return "";
        String first = src.split("#")[0];
        String[] parts = first.split("\\$");
        if (parts.length >= 2 && !TextUtils.isEmpty(parts[1])) return parts[1];
        try {
            JSONObject o = new JSONObject(src);
            String wd = o.optString("wd", "");
            if (!TextUtils.isEmpty(wd)) return wd;
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("ysbox", text == null ? "" : text));
            Notify.show("已复制到剪贴板");
        } catch (Throwable e) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}