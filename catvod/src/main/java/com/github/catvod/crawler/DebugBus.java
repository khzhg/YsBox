package com.github.catvod.crawler;

import android.util.Log;

/**
 * catvod -> app 调试总线桥接。
 *
 * catvod 是独立 module，不能反向依赖 app 层的 DebugLogBus。
 * 用反射做单向调用：找不到类（如 TV/leanback 变体没打这套 UI）就静默降级，
 * 绝不影响爬虫主流程。
 *
 * 缓存 Method 避免每条日志都反射查找。
 */
public final class DebugBus {

    private static final String TAG = "DebugBus";
    private static final String CLS = "com.fongmi.android.tv.debug.DebugLogBus";

    private static Object bus;
    private static java.lang.reflect.Method logMethod;
    private static boolean resolved;
    private static boolean available;

    private DebugBus() {
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> c = Class.forName(CLS);
            java.lang.reflect.Method get = c.getMethod("get");
            bus = get.invoke(null);
            if (bus == null) return;
            logMethod = c.getMethod("log", int.class, String.class, String.class);
            available = true;
        } catch (Throwable e) {
            // app 层未启用调试方案（或当前变体没打）——静默
            available = false;
            Log.d(TAG, "debug bus unavailable: " + e.getClass().getSimpleName());
        }
    }
    /** 转发一条脚本 console 日志（TYPE_CONSOLE = 0） */
    public static void relay(String tag, String message) {
        relay(0, tag, message);
    }

    /** 转发一条接口日志（TYPE_API = 1） */
    public static void relayApi(String tag, String message) {
        relay(1, tag, message);
    }

    /**
     * 转发一条系统日志（TYPE_SYSTEM = 2）。
     *
     * <p>框架自身的运行时通告（本地代理端口、nano 端口、爬虫代码载入成功等）
     * 都走这里。它们既不是脚本 console，也不是接口报文，混进控制台会把
     * 用户真正关心的 console.log 淹掉。</p>
     */
    public static void relaySystem(String tag, String message) {
        relay(2, tag, message);
    }

    private static void relay(int type, String tag, String message) {
        if (message == null || message.isEmpty()) return;
        resolve();
        if (!available) return;
        try {
            logMethod.invoke(bus, type, tag, message);
        } catch (Throwable ignored) {
            // 转发失败不影响爬虫
        }
    }
}
