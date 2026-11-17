package com.fongmi.android.tv.utils;


import com.fongmi.android.tv.ui.dialog.DialogThemeHelper;
import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (TextUtils.isEmpty(text) || isIgnorableConfigHint(text)) return;
        get().makeText(text);
    }

    private static boolean isIgnorableConfigHint(String text) {
        String value = text.replace(" ", "").toUpperCase();
        return value.contains("配置信息初始化失败")
                && (value.contains("重启APP") || value.contains("重新启动APP"));
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = DialogThemeHelper.builder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        // 两条硬约束，缺一条 Toast 就会被系统静默吞掉（用户反馈"没有返回 toast"）：
        // 1. 必须在主线程 show()——很多调用点来自 Task.execute 的工作线程；
        // 2. 必须用带 Looper 的线程 + 应用已在前台，否则 Android 11+ 直接丢弃。
        Runnable r = () -> {
            try {
                if (mToast != null) mToast.cancel();
                mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
                mToast.show();
            } catch (Throwable ignored) {
                // Toast 失败不能反噬调用方
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
