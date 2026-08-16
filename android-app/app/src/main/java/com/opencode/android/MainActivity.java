package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private SharedPreferences prefs;
    private String localUrl = "http://localhost:18888";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        localUrl = prefs.getString("server_url", localUrl);

        FrameLayout root = new FrameLayout(this);
        root.setId(1);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setBuiltInZoomControls(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(ProgressBar.VISIBLE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(ProgressBar.GONE);
                view.post(() -> {
                    String title = view.getTitle();
                    if (title != null && !title.isEmpty()) {
                        setTitle("OpenCode - " + title);
                    }
                });
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                progressBar.setVisibility(ProgressBar.GONE);
                statusView.setText("Server not reachable. Make sure opencode server is running.");
                statusView.setVisibility(TextView.VISIBLE);
            }
        });

        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        progressBar = new ProgressBar(this);
        progressBar.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        progressBar.setTranslationX(100);
        progressBar.setTranslationY(30);
        root.addView(progressBar);

        statusView = new TextView(this);
        statusView.setVisibility(TextView.GONE);
        statusView.setTextColor(0xFF888888);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(statusView);

        setContentView(root);
        webChromeClient(webView);
        webView.loadUrl(localUrl);

        startOpenCodeService();
        setupMenu();
    }

    private void webChromeClient(WebView webView) {
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(ProgressBar.GONE);
                }
                super.onProgressChanged(view, newProgress);
            }
        });
    }

    private void startOpenCodeService() {
        Intent svc = new Intent(this, OpenCodeService.class);
        svc.putExtra("auto_start", true);
        startService(svc);
    }

    private void setupMenu() {
        // minimal menu via options menu
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }
}