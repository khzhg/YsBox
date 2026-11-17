package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    // New MPV diagnostics use this gate. The 5.6.3 catvod base did not expose it.
    // Keep it off by default so release playback is not burdened by debug-only watchdogs.
    public static boolean isEnabled() {
        return false;
    }

    /**
     * Python 侧 print / traceback 的落地入口。
     *
     * <p>调用方是 {@code chaquo/src/main/python/webhtv_logging.py}：它把
     * {@code sys.stdout / sys.stderr} 包成 {@code _LogStream}，逐行调到这里
     * （Jython/Chaquopy 的 Java 桥直接调静态方法）。影视+ 的「爬虫: Python 输出」
     * 就是走的这条路。</p>
     *
     * <p><b>YsBox 原来缺这个方法</b>：Python 侧每次调 {@code console} 都抛
     * AttributeError，被 {@code _capture} 的 {@code except Exception} 静默吞掉——
     * py 源的 print 一条都进不了面板（用户对比影视+ 后反馈「py 没有输出」）。</p>
     *
     * <p>路由规则：stderr / Traceback → 「系统」；stdout → 「控制台」。
     * 控制台只留给脚本自己的输出，与 JS 的 console.log 同一栏。</p>
     */
    public static void console(String tag, String level, String message) {
        if (TextUtils.isEmpty(message)) return;
        // webhtv_logging.py 的 level 口径：stdout 固定 "INFO"；stderr 是
        // "STDERR"，若行首带 logging 前缀还会映射成 DEBUG/INFO/WARNING/ERROR 等。
        // 所以反过来判「输出」更稳：只有明确的输出级别进控制台，其余（含
        // STDERR / WARNING / ERROR / CRITICAL）归「系统」。
        boolean isOut = "INFO".equalsIgnoreCase(level) || "DEBUG".equalsIgnoreCase(level);
        if (isOut) {
            DebugBus.relay(tag, truncate(message));
        } else {
            DebugBus.relaySystem(tag, truncate(message));
        }
    }

    public static void log(Throwable th) {
        if (th != null) th.printStackTrace();
    }

    public static void log(String tag, Throwable th) {
        if (th != null) Logger.t(tag).e(th, "");
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg)) return;
        Logger.t(TAG).d(msg);
        // 镜像到调试悬浮窗（catvod 模块不依赖 app 层，走反射避免编译期耦合）
        //
        // 归到「系统」类而不是「控制台」：走到这里的都是框架自身的运行时通告
        // （"本地代理端口:9978"、"自定义爬虫代码载入成功！"、"nano port:30000"），
        // 它们跟源脚本无关。控制台这一栏只应该留给脚本自己的 console.log，
        // 否则用户根本找不到自己打的那几行。
        DebugBus.relaySystem(TAG, truncate(msg));
    }

    /**
     * 接口级调试日志（SiteApi 各步骤调用，标签形如 home / category / detail）。
     *
     * <p>归到「接口」类。这些是框架自动打的接口往返记录（含完整返回体），
     * 不是脚本作者写的那几行 console.log——控制台必须保持干净，
     * 否则用户自己打的调试信息会被这些自动日志淹掉。</p>
     *
     * <p>同时**必须截断**：home/detail 这类调用会把整段返回体（几千字符 JSON）
     * 塞进来，不截断会瞬间刷满面板。完整内容仍可在 Logcat 里找。</p>
     */
    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        Logger.t(tag).d(msg, args);
        try {
            String out = args == null || args.length == 0 ? msg : String.format(msg, args);
            DebugBus.relayApi(tag, truncate(out));
        } catch (Throwable ignored) {
        }
    }

    /** 调试面板单行长度上限：超出后截断，完整内容仍在 Logcat。 */
    private static final int MAX_RELAY_LENGTH = 300;

    private static String truncate(String s) {
        if (s == null) return "";
        // 压掉换行，保证一条日志就是一行（面板是逐行追加的）
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= MAX_RELAY_LENGTH) return flat;
        return flat.substring(0, MAX_RELAY_LENGTH) + "…";
    }

    public static void forceLog(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) Logger.t(tag).d(msg, args);
    }

    public static void forceLog(String tag, Throwable th) {
        if (th != null) Logger.t(tag).e(th, "");
    }
}
