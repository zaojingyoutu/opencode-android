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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCode Android APP
 *
 * 两种模式自动切换:
 * 1. 内置模式 (原生集成): APK 内嵌 opencode 二进制 (arm64-musl),
 *    本 APP 直接拉起 server 到 127.0.0.1:18888, 任何手机都能用。
 * 2. 外部模式: 连局域网/AidLux 上已有的 opencode server (server_url 可改)。
 *
 * 启动流程: 先 ping 外部地址 → 通就加载; 不通且有内置二进制 → 自动启动内置 server。
 */
public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 18888;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private SharedPreferences prefs;
    private String serverUrl;
    private ServerManager embedded;
    private boolean embeddedMode = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "http://127.0.0.1:" + DEFAULT_PORT);
        embedded = ServerManager.get(this);

        buildUI();
        setupRetry();
        tryConnect();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

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
                if (embeddedMode) {
                    statusView.setText("内置服务器连接失败\n" + failingUrl +
                            "\n\n点击重试 (可先查看上方 server 日志)");
                } else {
                    statusView.setText("外部服务器连接失败\n" + serverUrl +
                            "\n\n点击重试, 将自动启动内置服务器");
                }
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
        statusView.setHint("点击重试");
        root.addView(statusView);

        setContentView(root);
    }

    /** 打开即进入: 先快速探测外部 server, 不通则异步启动内置后端; UI 一直显示过渡状态 */
    private void tryConnect() {
        statusView.setText("正在启动 OpenCode...\n\n正在检测服务器连接");
        statusView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok = ping(serverUrl);
            runOnUiThread(() -> {
                if (ok) {
                    embeddedMode = false;
                    load();
                } else {
                    embeddedConnect();
                }
            });
        }).start();
    }

    /** 内置模式: 异步启动内嵌 server, 阶段进度实时显示, 就绪后自动载入 UI */
    private void embeddedConnect() {
        if (!embedded.isSupported()) {
            statusView.setText("当前设备不是 arm64, 无法使用内置服务器。\n\n" +
                    "请改用 AidLux 方案:\n" + serverUrl + "\n\n点击重试");
            return;
        }
        if (!embedded.hasEmbeddedBinary()) {
            statusView.setText("APK 未包含内置 opencode 二进制。\n\n" +
                    "请改用 AidLux 方案:\n" + serverUrl + "\n\n点击重试");
            return;
        }
        embeddedMode = true;
        embedded.start((ok, msg) -> {
            if (ok) {
                pollHealth(120);
            } else {
                statusView.setText("内置服务器启动失败\n" + msg + "\n\n" +
                        "日志:\n" + embedded.getLogTail(1500) + "\n\n点击重试");
            }
        }, stage -> runOnUiThread(() -> {
            statusView.setText(stage + "\n\n首次启动需要 1~2 分钟, 请耐心等待");
        }));
    }

    /** 每 1s ping 一次内置 server, 最多 seconds 秒 (首次启动含解压+加载 192MB 二进制, 放宽到 2 分钟) */
    private void pollHealth(final int seconds) {
        if (!polling.compareAndSet(false, true)) return;
        statusView.setText("OpenCode 服务启动中...\n\n正在等待就绪, 请稍候");
        final int[] waited = {0};
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!polling.get()) return;
                new Thread(() -> {
                    boolean ok = ping(embedded.serverUrl());
                    runOnUiThread(() -> {
                        if (ok) {
                            polling.set(false);
                            load();
                        } else if (waited[0] >= seconds) {
                            polling.set(false);
                            statusView.setText("内置服务器启动超时\n\n日志:\n" +
                                    embedded.getLogTail(2000) + "\n\n点击重试");
                        } else {
                            waited[0]++;
                            handler.postDelayed(this, 1000);
                        }
                    });
                }).start();
            }
        });
    }

    private void load() {
        statusView.setVisibility(View.GONE);
        webView.loadUrl(embeddedMode ? embedded.serverUrl() : serverUrl);
    }

    /** 点击状态文字重试 */
    private void setupRetry() {
        statusView.setOnClickListener(v -> {
            if (embeddedMode) {
                if (embedded.isStarting()) {
                    // 正在启动中, 不要打断, 重新等一轮
                    pollHealth(120);
                } else {
                    embedded.stop();
                    embeddedConnect();
                }
            } else {
                // 外部 server 失败 → 直接启动内置, 避免反复 ping 卡死
                embeddedConnect();
            }
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        polling.set(false);
        handler.removeCallbacksAndMessages(null);
        embedded.stop();
    }
}