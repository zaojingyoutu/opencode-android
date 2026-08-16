package com.opencode.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自包含 OpenCode APP: 内置 opencode 二进制 + musl libc，
 * 启动时自动运行 opencode serve，WebView 直连。
 * 首次启动需要用户填 API Key（一次性），写入 filesDir。
 */
public class MainActivity extends Activity {

    private static final int PORT = 18888;
    private static final String APP_NAME = "opencode";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private SharedPreferences prefs;
    private Process serverProcess;
    private boolean serverStarted = false;

    // 首次启动 API Key 设置界面
    private View setupPanel;
    private EditText apiKeyInput;
    private Button startBtn;
    private TextView setupStatus;
    private String localUrl = "http://127.0.0.1:" + PORT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setStatusBarColor(0xFF0E1116);

        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);

        setupPanel = buildSetupPanel();
        setContentView(setupPanel);

        // 检查 API Key
        String savedKey = prefs.getString("api_key", null);
        if (savedKey != null && !savedKey.isEmpty()) {
            startApp();
        } else {
            showSetup();
        }
    }

    private void showSetup() {
        setupStatus.setText("");
        apiKeyInput.setText(prefs.getString("api_key_hint", ""));
        apiKeyInput.setHint("粘贴 API Key（从 opencode.ai 获取）");
    }

    private View buildSetupPanel() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        // 标题
        TextView title = new TextView(this);
        title.setText("OpenCode");
        title.setTextSize(28);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 120, 0, 0);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("首次使用需要配置 API Key");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF8B949E);
        subtitle.setPadding(32, 12, 32, 32);
        root.addView(subtitle);

        // Key 输入
        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("ghp_xxx / sk-xxx / ...");
        apiKeyInput.setBackground(getDrawable(android.R.drawable.edit_text));
        int[] padding = {16, 16, 16, 16};
        apiKeyInput.setPadding(padding[0], padding[1], padding[2], padding[3]);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        inputParams.leftMargin = 64;
        inputParams.rightMargin = 64;
        inputParams.topMargin = 200;
        apiKeyInput.setLayoutParams(inputParams);
        root.addView(apiKeyInput);

        // 状态
        setupStatus = new TextView(this);
        setupStatus.setTextColor(0xFF7EE787);
        setupStatus.setPadding(32, 24, 32, 16);
        root.addView(setupStatus);

        // 启动按钮
        startBtn = new Button(this);
        startBtn.setText("启动 OpenCode");
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        btnParams.leftMargin = 80;
        btnParams.rightMargin = 80;
        btnParams.topMargin = 400;
        startBtn.setLayoutParams(btnParams);
        root.addView(startBtn);

        // 说明
        TextView help = new TextView(this);
        help.setText("• API Key 仅存于本设备本地\n• 首次启动将解压引擎（约 192MB）\n• 使用 OpenCode Zen 免费 DeepSeek 模型");
        help.setTextSize(12);
        help.setTextColor(0xFF6E7681);
        help.setPadding(32, 0, 32, 0);
        root.addView(help);

        // 布局约束：按钮在输入下方
        startBtn.setTranslationY(200);

        startBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                setupStatus.setTextColor(0xFFF85149);
                setupStatus.setText("请输入 API Key");
                return;
            }
            prefs.edit().putString("api_key", key).apply();
            prefs.edit().putBoolean("first_run", false).apply();
            startBtn.setEnabled(false);
            setupStatus.setTextColor(0xFF7EE787);
            setupStatus.setText("正在启动引擎...");
            new Thread(this::startApp).start();
        });

        return root;
    }

    private void startApp() {
        // 1. 确保 opencode 二进制就位
        String binPath = ensureBinary();
        if (binPath == null) {
            runOnUiThread(() -> {
                setupStatus.setTextColor(0xFFF85149);
                setupStatus.setText("❌ 引擎二进制缺失，请检查 APK 是否完整");
                startBtn.setEnabled(true);
            });
            return;
        }

        // 2. 确保 musl 库就位
        String libDir = ensureLibs();

        // 3. 启动 opencode serve
        startServer(binPath, libDir);
    }

    private String ensureBinary() {
        File binDir = new File(getFilesDir(), "bin");
        binDir.mkdirs();
        File bin = new File(binDir, APP_NAME);

        if (bin.exists() && bin.length() > 1000000) {
            return bin.getAbsolutePath();
        }

        try {
            InputStream is = getAssets().open("bin/" + APP_NAME);
            OutputStream os = new FileOutputStream(bin);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            os.close(); is.close();
            bin.setExecutable(true);
            return bin.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String ensureLibs() {
        File libDir = new File(getFilesDir(), "lib");
        libDir.mkdirs();

        // 从 assets/lib 拷贝所有 .so 到 filesDir/lib
        try {
            String[] libNames = getAssets().list("lib");
            if (libNames == null) return null;
            for (String name : libNames) {
                if (name.endsWith(".so") || name.endsWith(".so.")) {
                    File dest = new File(libDir, name);
                    if (!dest.exists()) {
                        InputStream is = getAssets().open("lib/" + name);
                        OutputStream os = new FileOutputStream(dest);
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                        os.close(); is.close();
                    }
                }
            }
            return libDir.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void startServer(String binPath, String libDir) {
        try {
            String key = prefs.getString("api_key", "");
            File envFile = new File(getFilesDir(), "opencode.env");
            if (!envFile.exists()) {
                writeFile(envFile, "OPENCODE_API_KEY=" + key + "\n");
            }

            File logFile = new File(getFilesDir(), "server.log");

            String cmd = String.format("LD_LIBRARY_PATH=%s %s serve --port %d --hostname 127.0.0.1 --print-logs",
                libDir, binPath, PORT);

            serverProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + " >> " + logFile.getAbsolutePath() + " 2>&1 &"});

            serverStarted = true;

            // 轮询直到 server 就绪
            new Thread(() -> pollUntilReady()).start();

        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> {
                if (setupPanel.isShown()) {
                    setupStatus.setTextColor(0xFFF85149);
                    setupStatus.setText("❌ 启动失败: " + e.getMessage());
                    startBtn.setEnabled(true);
                }
            });
        }
    }

    private void pollUntilReady() {
        for (int i = 0; i < 30; i++) {
            try {
                URL url = new URL(localUrl + "/api/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    serverStarted = true;
                    showWebView();
                    return;
                }
            } catch (Exception e) {
                // not ready yet
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
        runOnUiThread(() -> {
            if (setupPanel.isShown()) {
                setupStatus.setTextColor(0xFFF85149);
                setupStatus.setText("❌ 引擎启动超时，查看 server.log");
                startBtn.setEnabled(true);
            }
        });
    }

    private void showWebView() {
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setBuiltInZoomControls(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

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
                statusView.setText("Server not reachable\n" + description);
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

        progressBar = new ProgressBar(this);
        progressBar.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setVisibility(View.GONE);
        statusView.setTextColor(0xFF888888);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setText("正在加载...");
        statusView.setPadding(32, 0, 32, 0);

        root.addView(webView);
        root.addView(progressBar);
        root.addView(statusView);
        setContentView(root);

        webView.loadUrl(localUrl);
    }

    private void writeFile(File f, String content) throws IOException {
        try (FileWriter w = new FileWriter(f)) { w.write(content); }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && setupPanel != null && setupPanel.isShown()) {
            return true; // 拦截返回键
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
        if (serverProcess != null) serverProcess.destroy();
    }
}
