package com.fongmi.quickjs.method;

import android.text.TextUtils;

import com.github.catvod.crawler.DebugBus;
import com.orhanobut.logger.Logger;
import com.whl.quickjs.wrapper.QuickJSContext;

/**
 * 源脚本 console.log / info / warn / error 的落地实现。
 *
 * <p>这是「控制台」这一栏唯一的数据来源。框架自身的通告（本地代理端口、
 * 载入成功、接口往返）都走 DebugBus.relaySystem / relayApi，不进这里——
 * 否则源作者自己打的调试行会被框架日志淹掉。</p>
 *
 * <p>面板是逐行追加的，所以换行要先压平；再按 MAX_LEN 截断，
 * 完整内容始终保留在 Logcat（tag=quickjs）。</p>
 */
public class Console implements QuickJSContext.Console {

    private static final String TAG = "quickjs";

    /** 单行最多保留的字符数：面板宽度有限，超长 JSON 会撑爆一行。 */
    private static final int MAX_LEN = 300;

    @Override
    public void log(String info) {
        Logger.t(TAG).d(info);
        relay(info);
    }

    @Override
    public void info(String info) {
        Logger.t(TAG).i(info);
        relay(info);
    }

    @Override
    public void warn(String info) {
        Logger.t(TAG).w(info);
        relay(info);
    }

    @Override
    public void error(String info) {
        Logger.t(TAG).e(info);
        relay(info);
    }

    /** 镜像到调试面板的「控制台」。tag 留空：源里看到的应该就是自己打的原文。 */
    private void relay(String info) {
        if (TextUtils.isEmpty(info)) return;
        try {
            DebugBus.relay("", truncate(info));
        } catch (Throwable ignored) {
            // 调试通道失败绝不能影响脚本执行
        }
    }

    private String truncate(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= MAX_LEN) return flat;
        return flat.substring(0, MAX_LEN) + "…";
    }
}