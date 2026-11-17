package com.fongmi.android.tv.read;

import android.content.Context;

import com.fongmi.android.tv.ui.reader.ComicReaderActivity;
import com.fongmi.android.tv.ui.reader.NovelReaderActivity;

/**
 * 阅读器路由：按会话类型分发到漫画/小说阅读器。
 *
 * 原实现是占位（trae 分支未移植阅读器），这里补上真实路由。
 * 会话可能带懒取字段（chapterUrl/ruleTitle 等）尚未填充正文/图片，
 * 阅读器内部按需处理：有内容直接渲染，为空则提示。
 */
public class ReadingRouter {

    public static void openReading(Context context, ReadingSession session) {
        openReadingWithStartPage(context, session, 0);
    }

    public static void openReadingWithStartPage(Context context, ReadingSession session, int startPage) {
        if (context == null || session == null) return;
        android.app.Activity activity = context instanceof android.app.Activity
                ? (android.app.Activity) context : null;
        if (activity == null) return;
        if (session.kind == ReadingSession.Kind.NOVEL) {
            NovelReaderActivity.start(activity, session, session.startFraction);
        } else {
            ComicReaderActivity.start(activity, session, startPage);
        }
    }
}
