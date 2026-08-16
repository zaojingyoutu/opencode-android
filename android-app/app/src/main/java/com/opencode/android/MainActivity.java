package com.opencode.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OpenCode Android APP (AidLux 方案)
 *
 * AidLux = Android + Linux 双系统。
 * opencode server 跑在 AidLux 的 Linux 侧（本机 localhost:18888），
 * 这个 APP 只是一个轻量 WebView 前端。
 *
 * 启动 server 的方法（AidLux 终端一次）:
 *   opencode serve --port 18888 --hostname 0.0.0.0
 *
 * 也可以填局域网 IP 让局域网内其他设备访问。
 */
public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 18888;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private SharedPreferences prefs;
    private String serverUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "http://127.0.0.1:" + DEFAULT_PORT);

        buildUI();
        checkServer();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        // WebView
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setBuiltInZoomControls(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                statusView.setVisibility(View.GONE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                progressBar.setVisibility(View.GONE);
                statusView.setText("无法连接到服务器\n" + serverUrl + "\n\n" +
                        "请在 AidLux 终端运行:\nopencode serve --port 18888 --hostname 0.0.0.0");
                statusView.setVisibility(View.VISIBLE);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress >= 100) progressBar.setVisibility(View.GONE);
            }
        });

        root.addView(webView);

        progressBar = new ProgressBar(this);
        root.addView(progressBar);

        statusView = new TextView(this);
        statusView.setVisibility(View.GONE);
        statusView.setTextColor(0xFF8B949E);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setPadding(48, 0, 48, 0);
        statusView.setTextSize(14);
        root.addView(statusView);

        setContentView(root);
    }

    /** 启动时先检查 server, 通就直接加载, 不通显示提示 (不自动重载避免循环) */
    private void checkServer() {
        new Thread(() -> {
            boolean ok = ping(serverUrl);
            runOnUiThread(() -> {
                if (ok) {
                    statusView.setVisibility(View.GONE);
                    webView.loadUrl(serverUrl);
                } else {
                    statusView.setText("无法连接到服务器\n" + serverUrl + "\n\n" +
                        "在 AidLux 终端运行:\nopencode serve --port 18888 --hostname 0.0.0.0\n\n" +
                        "然后回到本 APP 重试\n(服务器地址可在设置里修改)");
                    statusView.setVisibility(View.VISIBLE);
                    setupLongPressRetry();
                }
            });
        }).start();
    }

    private void setupLongPressRetry() {
        statusView.setOnClickListener(v -> {
            // 点状态文字重试
            statusView.setText("连接中...");
            new Thread(() -> {
                boolean ok = ping(serverUrl);
                runOnUiThread(() -> {
                    if (ok) {
                        statusView.setVisibility(View.GONE);
                        webView.loadUrl(serverUrl);
                    } else {
                        statusView.setText("仍然连不上 " + serverUrl +
                            "\n确认 AidLux 侧 opencode 已启动");
                        statusView.setVisibility(View.VISIBLE);
                    }
                });
            }).start();
        });
        statusView.setHint("点击重试");
    }

    private boolean ping(String url) {
        try {
            URL u = new URL(url + "/api/health");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }
}