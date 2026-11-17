package com.fongmi.android.tv.debug;

import android.app.Activity;

import com.github.catvod.utils.Prefers;

/**
 * 调试总开关。
 *
 * 对齐商业版的 logcat_capture 键：一个 Prefers 布尔值控制全局调试采集，
 * 关闭时 DebugLogBus 不记录、悬浮窗不显示，对运行时零开销。
 *
 * 入口设计（比商业版更好用）：
 * 1. 连续点击版本号 7 次解锁（沿用商业版习惯）
 * 2. Lab 实验室页加显式开关，不用记点击次数
 * 3. 长按悬浮窗圆点即可关闭
 */
public class DebugCenter {

    private static final String KEY_ENABLED = "logcat_capture";
    private static final String KEY_UNLOCKED = "debug_unlocked";

    /** 解锁所需连续点击次数（对齐商业版） */
    private static final int UNLOCK_CLICKS = 7;

    private static int clickCount;
    private static long lastClickTime;

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
        DebugLogBus.get().setCapture(enabled);
        if (enabled) {
            DebugLogBus.get().log(DebugLogBus.TYPE_SYSTEM, "Debug", "调试已开启");
            LogcatCapture.start();
        } else {
            LogcatCapture.stop();
            DebugFloatWindow.get().dismiss();
            DebugLogBus.get().clear();
        }
    }

    public static boolean isUnlocked() {
        return Prefers.getBoolean(KEY_UNLOCKED);
    }

    public static void unlock() {
        Prefers.put(KEY_UNLOCKED, true);
    }

    /**
     * 连续点击计数：达到阈值自动解锁调试。
     * 间隔超过 1.5 秒重置，防止误触。
     *
     * @return true 表示本次点击触发了解锁
     */
    public static boolean tap() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime > 1500) clickCount = 0;
        lastClickTime = now;
        clickCount++;
        if (clickCount >= UNLOCK_CLICKS) {
            clickCount = 0;
            unlock();
            setEnabled(true);
            return true;
        }
        return false;
    }

    /** 在当前 Activity 上显示调试悬浮窗（前提：已开关） */
    public static void attach(Activity activity) {
        if (!isEnabled()) return;
        DebugLogBus.get().setCapture(true);
        LogcatCapture.start();
        DebugFloatWindow.get().show(activity);
    }

    /** 页面切换时重新附着（TYPE_APPLICATION_PANEL 依附 Activity） */
    public static void detach() {
        DebugFloatWindow.get().dismiss();
    }
}
