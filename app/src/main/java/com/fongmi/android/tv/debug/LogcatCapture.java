package com.fongmi.android.tv.debug;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logcat 采集：用 Runtime.exec 起 `logcat -v time` 子进程，逐行读回。
 *
 * 为什么不用 logcat -s 过滤：
 * 本 App 关心的 tag 分散在 okhttp / quickjs / ijk / 自有 tag 上，
 * 写死过滤表会漏；所以全量读，再在 Java 层按进程 PID 过滤，
 * 只保留本进程的日志（避免把系统其它 App 的日志刷进来）。
 *
 * 子进程在 start/stop 之间常驻，读线程是 daemon，不会阻塞退出。
 * 全部异常静默——采集失败绝不能影响播放。
 */
public final class LogcatCapture {

    private static final String TAG = "LogcatCapture";

    /** logcat -v time 行格式：`01-01 12:00:00.000 I/Tag   ( 1234): msg` */
    private static final int MAX_LINE = 2000;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static Process process;
    private static Thread worker;
    private static String pid;

    private LogcatCapture() {
    }

    public static synchronized void start() {
        if (running.get()) return;
        pid = String.valueOf(android.os.Process.myPid());
        try {
            ProcessBuilder pb = new ProcessBuilder("logcat", "-v", "time");
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (Throwable e) {
            Log.d(TAG, "start failed: " + e.getClass().getSimpleName());
            return;
        }
        running.set(true);
        worker = new Thread(LogcatCapture::loop, "logcat-capture");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void stop() {
        running.set(false);
        Thread t = worker;
        worker = null;
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void loop() {
        Process p = process;
        if (p == null) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                emit(line);
            }
        } catch (Throwable ignored) {
            // 进程被 destroy 时会抛，属正常退出
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 解析一行，只保留本进程的，拆成 tag / msg 投进总线 */
    private static void emit(String raw) {
        if (raw == null || raw.isEmpty()) return;
        if (raw.length() > MAX_LINE) raw = raw.substring(0, MAX_LINE);

        String body = raw;
        String tag = "logcat";
        // 去掉时间戳前缀：`MM-DD HH:MM:SS.mmm `
        int timeEnd = raw.indexOf(' ', raw.indexOf(' ') + 1);
        if (timeEnd > 0 && timeEnd + 1 < raw.length()) {
            String rest = raw.substring(timeEnd + 1).trim();
            // rest 形如 `I/Tag( 1234): msg`
            int slash = rest.indexOf('/');
            int paren = rest.indexOf('(');
            int colon = rest.indexOf(": ");
            if (slash == 1 && paren > slash) {
                tag = rest.substring(slash + 1, paren).trim();
                if (colon > 0) body = rest.substring(colon + 2);
                else body = rest;
            } else {
                body = rest;
            }
        }
        if (tag.isEmpty()) tag = "logcat";

        // PID 过滤：只保留本进程的日志，避免系统里其它 App 刷屏。
        //
        // 例外：Python 的 print。Chaquopy 默认把 sys.stdout / stderr
        // 重定向到 logcat，tag 为 python / python.stdout / python.stderr。
        // 这类行必须放行——否则用户「只看到 JS 打印，Python 打印一条没有」。
        // （Chaquopy 是 in-process 的，本来也是本进程日志，放行不会引入噪音。）
        boolean isPython = tag.toLowerCase(java.util.Locale.US).startsWith("python");
        if (!isPython && pid != null
                && raw.indexOf("( " + pid + ")") < 0 && raw.indexOf("(" + pid + ")") < 0) return;

        DebugLogBus.get().log(DebugLogBus.TYPE_LOGCAT, tag, body);
    }
}
