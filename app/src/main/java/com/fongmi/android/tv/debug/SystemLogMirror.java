package com.fongmi.android.tv.debug;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 拦截 {@code System.out} / {@code System.err}，把输出镜像进调试总线。
 *
 * <p><b>为什么需要它</b>：内嵌的 Python 由 Chaquopy 提供，跑在 App 同一个
 * 进程里。Python 的 {@code print()} 最终写到 JVM 的 {@code System.out}，
 * 这条通道<b>既不经过 {@code android.util.Log}，也不经过 Logger</b>：
 * </p>
 * <ul>
 *   <li>{@link LogcatCapture} 读的是 logcat，收不到；</li>
 *   <li>{@link DebugLogAdapter} 只镜像 Logger，收不到；</li>
 *   <li>{@code TYPE_CONSOLE} 桶此前根本没有任何投递方。</li>
 * </ul>
 * <p>结果就是用户反馈的"只显示 JS 的打印，Python 的 print 一条看不到"。</p>
 *
 * <p><b>安全性</b>：写回原流（保留 logcat 既有行为），且所有异常静默——
 * 日志采集绝不能影响业务输出。重复调用无副作用。</p>
 */
public final class SystemLogMirror {

    /** 单行最大长度，防止超长输出把面板刷爆 */
    private static final int MAX_LINE = 2000;

    /**
     * 流标签。
     *
     * <p><b>刻意不叫「Python」</b>。这两条流是 JVM 级别的，Python 的 print
     * 只是来源之一；App 里大量 Java 代码用 {@code e.printStackTrace()}
     * 往 stderr 写异常栈。把 stderr 标成「Python错误」，就会把 Java 异常
     * 误报成 Python 故障——用户截图里那个
     * {@code ConnectException: raw.githubusercontent.com}（okhttp 连接失败）
     * 被标成【Python错误】就是这么来的。按流本身的语义命名才准确。</p>
     */
    private static final String TAG_STDOUT = "输出";
    private static final String TAG_STDERR = "错误";

    private SystemLogMirror() {
    }

    /** 安装镜像。可重复调用，重复调用无副作用。 */
    public static void install() {
        try {
            PrintStream out = System.out;
            if (!isInstalled(out)) System.setOut(wrap(out, TAG_STDOUT));
            PrintStream err = System.err;
            if (!isInstalled(err)) System.setErr(wrap(err, TAG_STDERR));
        } catch (Throwable ignored) {
            // 安装失败不能让启动崩溃
        }
    }

    /** 是否已经包过（避免多层包裹导致同一条日志重复投递）。 */
    private static boolean isInstalled(PrintStream ps) {
        return ps != null && ps.toString() != null && ps.toString().contains(Mirror.class.getName());
    }

    private static PrintStream wrap(PrintStream target, String tag) {
        try {
            return new MirrorPrintStream(target, tag);
        } catch (Throwable ignored) {
            return target;
        }
    }

    /**
     * 带标记的 PrintStream。
     *
     * <p>覆写 {@code toString()} 是为了让 {@link #isInstalled} 能识别
     * "这个流已经被我包过"，否则重复 install 会在每条日志上叠加多层</p>
     */
    private static class MirrorPrintStream extends PrintStream {
        MirrorPrintStream(PrintStream target, String tag) {
            super(new Mirror(target, tag), true);
        }

        @Override
        public String toString() {
            return getClass().getName();
        }
    }

    /**
     * 按行切分的转发流。
     *
     * <p>用字节缓冲 + UTF-8 解码，而不是逐字节转 char——Python 输出里
     * 中文很常见，逐字节转会乱码。</p>
     */
    private static class Mirror extends OutputStream {

        private final PrintStream target;
        private final String tag;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Mirror(PrintStream target, String tag) {
            this.target = target;
            this.tag = tag;
        }

        @Override
        public void write(int b) {
            // 原样写回，保留 logcat 里的既有输出
            try {
                target.write(b);
            } catch (Throwable ignored) {
            }
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                if (buffer.size() < MAX_LINE) buffer.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                target.write(b, off, len);
            } catch (Throwable ignored) {
            }
            for (int i = off; i < off + len; i++) {
                int v = b[i] & 0xFF;
                if (v == '\n') {
                    flushLine();
                } else if (v != '\r') {
                    if (buffer.size() < MAX_LINE) buffer.write(v);
                }
            }
        }
        /** 一行结束：解码后投进总线。 */
        private void flushLine() {
            if (buffer.size() == 0) return;
            String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            if (line.trim().isEmpty()) return;

            // 按「流」分类，而不是按行内容去猜。
            //
            // 这两条流本身就是最可靠的依据：
            //   stderr ← Java 的 e.printStackTrace()、Python 的 traceback
            //   stdout ← Python 的 print()
            //
            // 早前我按行的关键字猜（contains("Exception") / startsWith("Caused by")），
            // 结果一段异常栈被活生生拆到两个 Tab：栈头 `ConnectException` 判进「系统」，
            // 而续行 `... 7 more` 三个特征一个都不沾，被判成「输出」丢进控制台——
            // 用户看到的控制台就只剩一列 `... 7 more`（截图反馈的正是这个）。
            // 按流分类不存在这个问题：整段栈要么全在系统，要么全在控制台。
            if (TAG_STDERR.equals(tag)) {
                DebugLogBus.get().log(DebugLogBus.TYPE_SYSTEM, TAG_STDERR, line);
            } else {
                DebugLogBus.get().log(DebugLogBus.TYPE_CONSOLE, TAG_STDOUT, line);
            }
        }


        @Override
        public void flush() {
            try {
                target.flush();
            } catch (Throwable ignored) {
            }
        }
    }
}
