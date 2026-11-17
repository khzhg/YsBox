package com.fongmi.android.tv.ui.action;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 内嵌网页页（对应 action 的 {@code type:'webview'}）。
 *
 * <p>数据源常把本地服务页（如 {@code http://127.0.0.1:9978/}）或配置页放进 action，
 * 客户端需要在应用内打开它，而不是甩给系统浏览器——否则用户会跳出 App、丢上下文。</p>
 *
 * <p>细节：</p>
 * <ul>
 *   <li>同一 Cookie 仓库，登录态与 WebView 抓包链共用；</li>
 *   <li>顶部保留一条返回栏，避免无返回路径；</li>
 *   <li>支持 textZoom / height 这类来自 action 的显示参数。</li>
 * </ul>
 */
public class ActionWebActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ZOOM = "zoom";
    private static final String EXTRA_HEIGHT = "height";

    private WebView web;
    private ProgressBar bar;

    public static void start(Activity activity, ActionSpec spec) {
        if (activity == null || spec == null) return;
        try {
            Intent it = new Intent(activity, ActionWebActivity.class);
            it.putExtra(EXTRA_URL, spec.qrcode.isEmpty() ? spec.content : spec.qrcode);
            it.putExtra(EXTRA_TITLE, TextUtils.isEmpty(spec.title) ? "网页" : spec.title);
            it.putExtra(EXTRA_ZOOM, spec.qrcodeSize);
            it.putExtra(EXTRA_HEIGHT, spec.height);
            activity.startActivity(it);
        } catch (Throwable e) {
            Toast.makeText(activity, "无法打开网页：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void start(Activity activity, String url, String title, int textZoom) {
        if (activity == null || TextUtils.isEmpty(url)) return;
        Intent it = new Intent(activity, ActionWebActivity.class);
        it.putExtra(EXTRA_URL, url);
        it.putExtra(EXTRA_TITLE, TextUtils.isEmpty(title) ? "网页" : title);
        it.putExtra(EXTRA_ZOOM, textZoom);
        activity.startActivity(it);
    }

    /** 直接从 url 打开（不指定缩放）。 */
    public static void start(Activity activity, String url, String title) {
        start(activity, url, title, 0);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        int zoom = getIntent().getIntExtra(EXTRA_ZOOM, 0);

        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "未提供网址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：返回 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF1565C0);
        top.setPadding(dp(6), 0, dp(12), 0);

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(Color.WHITE);
        back.setTextSize(26);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 用系统浏览器打开（有些站点在 WebView 里受限）
        TextView open = new TextView(this);
        open.setText("\u5916\u90e8");
        open.setTextColor(0xFFCCE4FF);
        open.setTextSize(13);
        open.setPadding(dp(10), dp(8), dp(4), dp(8));
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {
            }
        });
        top.addView(open);

        col.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setupWeb(zoom);
        web.loadUrl(url);
        setContentView(root);
    }

    private void setupWeb(int zoom) {
        try {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            if (zoom > 0 && zoom <= 100) s.setTextZoom(zoom);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignored) {
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (bar == null) return;
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false;
            }
        });
    }

    @Override
    public void onBackPressed() {
        try {
            if (web != null && web.canGoBack()) {
                web.goBack();
                return;
            }
        } catch (Throwable ignored) {
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.stopLoading();
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}