package com.fongmi.android.tv.debug;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 调试日志总线：所有调试来源（蜘蛛脚本 console、播放器、系统日志）统一汇入这里，
 * 再由悬浮窗 / 对话框订阅渲染。
 *
 * 设计要点（对齐商业版实现）：
 * 1. 分类隔离：CONSOLE / SYSTEM 各自独立环形缓冲，切换 Tab 不串台。
 * 2. 主线程投递：日志可能来自任意线程（含 Python 爬虫线程），统一 post 到主线程，
 *    避免 RecyclerView / TextView 跨线程操作崩溃。
 * 3. 容量封顶：环形缓冲默认 500 条，防止长时间运行 OOM。
 * 4. 时间戳在写入时刻生成，而不是渲染时刻——否则同一批日志会显示同一时间。
 */
public class DebugLogBus {

    /** 日志分类：对应悬浮窗 Tab */
    public static final int TYPE_CONSOLE = 0;
    public static final int TYPE_API = 1;
    public static final int TYPE_SYSTEM = 2;
    public static final int TYPE_LOGCAT = 3;

    /** 分类总数 */
    public static final int TYPE_COUNT = 4;

    /** 单类最大保留条数 */
    private static final int MAX_LINES = 500;

    private static final DebugLogBus INSTANCE = new DebugLogBus();

    public static DebugLogBus get() {
        return INSTANCE;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 全局递增序号：跨桶排序用。 */
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
    private final SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final List<ArrayDeque<Entry>> buckets = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<String> filters = new ArrayList<>();

    /** 采集开关：关闭时 log() 直接返回，零开销 */
    private volatile boolean capture;

    private DebugLogBus() {
        for (int i = 0; i < TYPE_COUNT; i++) buckets.add(new ArrayDeque<>());
    }

    /** 一条日志记录 */
    public static class Entry {
        public final int type;
        public final String time;
        public final String tag;
        public final String message;
        public final long elapsed;
        /**
         * 全局递增序号，反映真实写入顺序。
         *
         * <p>「全部」Tab 要合并四个桶，只能靠它排序。
         * 早前误用 {@link #elapsed}（这是耗时的毫秒数，多数日志是 0），
         * 排序结果基本等于随机。</p>
         */
        public final long seq;

        Entry(int type, String time, String tag, String message, long elapsed, long seq) {
            this.type = type;
            this.time = time;
            this.tag = tag == null ? "" : tag;
            this.message = message == null ? "" : message;
            this.elapsed = elapsed;
            this.seq = seq;
        }

        /**
         * 渲染成一行。
         *
         * <p>格式对齐影视+：{@code 09:13:55.279 【标签】 内容}。
         * 时间不加方括号、标签用【】包裹，扫读时列对齐更整齐；
         * 耗时信息只在有值时追加。</p>
         */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(time).append(" ");
            if (!TextUtils.isEmpty(tag)) sb.append("【").append(tag).append("】 ");
            sb.append(message);
            if (elapsed > 0) sb.append(" (").append(elapsed).append("ms)");
            return sb.toString();
        }

        /** 关键字命中：标签或内容任一包含即算命中 */
        public boolean matches(String keyword) {
            if (TextUtils.isEmpty(keyword)) return true;
            String k = keyword.toLowerCase(Locale.US);
            return tag.toLowerCase(Locale.US).contains(k) || message.toLowerCase(Locale.US).contains(k);
        }
    }

    /** 订阅回调（始终在主线程） */
    public interface Listener {
        void onLogAdded(Entry entry);

        void onCleared();
    }

    public void setCapture(boolean capture) {
        this.capture = capture;
    }

    public boolean isCapture() {
        return capture;
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        handler.post(() -> {
            if (!listeners.contains(listener)) listeners.add(listener);
        });
    }

    public void removeListener(Listener listener) {
        handler.post(() -> listeners.remove(listener));
    }

    /** 写入一条日志（线程安全，可在任意线程调用） */
    public void log(int type, String tag, String message) {
        log(type, tag, message, 0);
    }

    public void log(int type, String tag, String message, long elapsed) {
        if (TextUtils.isEmpty(message)) return;
        // 总开关关闭时不记录：对运行时零开销，也避免无形中积压内存。
        if (!capture) return;
        final int t = type < 0 || type >= TYPE_COUNT ? TYPE_CONSOLE : type;
        final String time = format.format(new Date());
        // 序号在投递前取，保证与真实写入顺序一致（handler.post 是异步的）
        final long order = seq.incrementAndGet();
        handler.post(() -> {
            ArrayDeque<Entry> bucket = buckets.get(t);
            while (bucket.size() >= MAX_LINES) bucket.pollFirst();
            Entry entry = new Entry(t, time, tag, message, elapsed, order);
            bucket.addLast(entry);
            for (Listener l : new ArrayList<>(listeners)) {
                try {
                    l.onLogAdded(entry);
                } catch (Throwable ignored) {
                    // 单个监听器异常不能影响其它监听器
                }
            }
        });
    }

    /** 取某一分类的全部日志（需在主线程调用） */
    public List<Entry> lines(int type) {
        int t = type < 0 || type >= TYPE_COUNT ? TYPE_CONSOLE : type;
        return new ArrayList<>(buckets.get(t));
    }

    /** 按关键字过滤（需在主线程调用） */
    public List<Entry> filtered(int type, String keyword) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : lines(type)) if (e.matches(keyword)) result.add(e);
        return result;
    }

    public int size(int type) {
        int t = type < 0 || type >= TYPE_COUNT ? TYPE_CONSOLE : type;
        return buckets.get(t).size();
    }

    public void clear() {
        handler.post(() -> {
            for (ArrayDeque<Entry> b : buckets) b.clear();
            for (Listener l : new ArrayList<>(listeners)) {
                try {
                    l.onCleared();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 导出全部日志为文本 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buckets.size(); i++) {
            for (Entry e : new ArrayList<>(buckets.get(i))) sb.append(e.line()).append("\n");
        }
        return sb.toString();
    }
}
