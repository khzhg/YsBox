package com.fongmi.android.tv.debug;

import android.util.Log;

import com.orhanobut.logger.LogAdapter;

/**
 * 接管 orhanobut Logger，把全 App 的日志镜像一份到 DebugLogBus。
 *
 * 对应商业版的 DebugLogAdapter。接入点在 Startup.create()——
 * 替换原本的 AndroidLogAdapter 即可，业务代码无需改动。
 *
 * 注意：Logger 2.x 的 LogAdapter 接口就两个方法，
 * isLoggable 决定是否打印，log 负责实际输出。
 * 这里既转发给默认 logcat，也镜像到总线。
 */
public class DebugLogAdapter implements LogAdapter {

    /** 是否镜像到调试总线（由调试开关控制，避免常驻开销） */
    private boolean mirror = true;

    public DebugLogAdapter mirror(boolean mirror) {
        this.mirror = mirror;
        return this;
    }

    @Override
    public boolean isLoggable(int priority, String tag) {
        return true;
    }

    @Override
    public void log(int priority, String tag, String message) {
        // 保留原本的 logcat 行为，不改变现有调试习惯
        Log.println(priority, tag, message);
        if (!mirror) return;
        DebugLogBus.get().log(DebugLogBus.TYPE_SYSTEM, tag, message);
    }
}
