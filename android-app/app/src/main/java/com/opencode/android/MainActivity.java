package com.opencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ValueCallback;
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
 * 内置模式: APK 内嵌完整 Alpine Linux (arm64) 容器 (PRoot), 本 APP 直接拉起
 * opencode server 到 127.0.0.1:18888, 任何手机都能用。
 * 打开即显示加载界面, server 就绪后自动载入 Web UI。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private ProgressBar spinner;
    private FrameLayout startupOverlay;
    private SharedPreferences prefs;
    private ServerManager embedded;
    private boolean hadAllFilesAccess;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private boolean wasBackgrounded = false;
    /** App 是否在前台 (供 ServerService 看护线程判断"用户是否在看", 决定要不要发完成通知) */
    public static volatile boolean foreground;
    private boolean pendingFileChooser = false;
    private boolean pageFailed = false;
    private long lastPauseElapsed;
    /** 进入后台时的墙上时间, 用于和 server 侧会话更新时间比较, 判断离开期间有无进展 */
    private long lastPauseWall;
    // 后台超过该时长才在回前台时刷新页面 (快速切回/文件选择返回不打断使用)
    private static final long BG_REFRESH_THRESHOLD_MS = 30_000;
    private static final int FILECHOOSER_RESULT_CODE = 1001;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        embedded = ServerManager.get(this);
        hadAllFilesAccess = Environment.isExternalStorageManager();

        buildUI();
        setupRetry();
        startServer();
        maybePromptAllFilesAccess();
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
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageFailed = false;
                progressBar.setProgress(5);
                progressBar.setVisibility(View.VISIBLE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                embedded.noteClientActivity();
                if (pageFailed) return;
                // 页面加载完成: 隐藏加载覆盖层 (避免空白闪烁, 首次打开/后台恢复统一走这里)
                startupOverlay.setVisibility(View.GONE);
                // opencode 网页的错误信息在窄屏不换行, 注入 CSS 强制长文本折行
                view.evaluateJavascript(
                        "var s=document.createElement('style');" +
                        "s.innerHTML='*{overflow-wrap:break-word!important;word-break:break-word!important;max-width:100%!important}';" +
                        "document.head.appendChild(s);", null);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                pageFailed = true;
                progressBar.setVisibility(View.GONE);
                error("内置服务器连接失败\n" + failingUrl +
                        "\n\n点击重试 (可先查看上方 server 日志)");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                    FileChooserParams params) {
                // 上一次弹窗还没返回时, 先通知它取消
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = cb;
                pendingFileChooser = true;
                try {
                    startActivityForResult(params.createIntent(), FILECHOOSER_RESULT_CODE);
                    return true;
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
            }
        });

        root.addView(webView);

        // 顶部细进度条 (Web UI 页面加载)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF3FB950));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x333FB950));
        FrameLayout.LayoutParams pbp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbp.gravity = android.view.Gravity.TOP;
        progressBar.setLayoutParams(pbp);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        // 启动覆盖层: 渐变底 + Logo + 状态
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF161B22, 0xFF0D1117});
        startupOverlay = new FrameLayout(this);
        startupOverlay.setBackground(bg);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(40), 0, dp(40), 0);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.CENTER;
        box.setLayoutParams(blp);

        ImageView logo = new ImageView(this);
        logo.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(84), dp(84));
        logo.setLayoutParams(llp);
        box.addView(logo);

        TextView title = new TextView(this);
        title.setText("OpenCode");
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFE6EDF3);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(24);
        title.setLayoutParams(tlp);
        box.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("AI 编程助手 · 内置 Linux 环境");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF8B949E);
        subtitle.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        subtitle.setLayoutParams(slp);
        box.addView(subtitle);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF3FB950));
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spinLp.topMargin = dp(32);
        spinLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        spinner.setLayoutParams(spinLp);
        box.addView(spinner);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF8B949E);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sllp.topMargin = dp(20);
        statusView.setLayoutParams(sllp);
        box.addView(statusView);

        startupOverlay.addView(box);
        root.addView(startupOverlay);

        setContentView(root);
    }

    /** 显示启动覆盖层 */
    private void showOverlay() {
        startupOverlay.setVisibility(View.VISIBLE);
    }

    /** 加载中状态: 转圈 + 文案 */
    private void busy(String text) {
        showOverlay();
        spinner.setVisibility(View.VISIBLE);
        statusView.setText(text);
    }

    /** 错误状态: 停转圈, 显示错误文案 (点击覆盖层重试) */
    private void error(String text) {
        showOverlay();
        spinner.setVisibility(View.GONE);
        statusView.setText(text);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 打开即进入: 后台服务仍在跑则直接连上, 否则异步启动内置 server; UI 一直显示过渡状态 */
    private void startServer() {
        if (embedded.isRunning()) {
            // 上次的后台服务还在跑, 直接连上, 不再等待
            ensureServerService();
            load();
            return;
        }
        embeddedConnect();
    }

    /** 内置模式: 异步启动内嵌 server, 阶段进度实时显示, 就绪后自动载入 UI */
    private void embeddedConnect() {
        if (!embedded.isSupported()) {
            error("当前设备不是 arm64, 无法使用内置服务器。\n\n点击重试");
            return;
        }
        if (!embedded.hasEmbeddedBinary()) {
            error("APK 未包含内置 opencode 运行环境。\n\n点击重试");
            return;
        }
        ensureServerService();
        ensureNotificationPermission();
        embedded.start((ok, msg) -> {
            // "已在运行"/"正在启动中" 是并发启动时的正常返回值, 不视为失败
            if (ok || (msg != null && (msg.contains("已在运行") || msg.contains("正在启动中")))) {
                maybePromptBatteryOptimization();
                pollHealth(120);
            } else {
                error("内置服务器启动失败\n" + msg + "\n\n" +
                        "日志:\n" + embedded.getLogTail(1500) + "\n\n点击重试");
            }
        }, stage -> runOnUiThread(() -> {
            busy(stage + "\n\n首次启动约需 30 秒~1 分钟, 请稍候");
        }));
    }

    /** 每 1s ping 一次内置 server, 最多 seconds 秒 (首次启动含解压+加载 192MB 二进制, 放宽到 2 分钟) */
    private void pollHealth(final int seconds) {
        if (!polling.compareAndSet(false, true)) return;
        busy("OpenCode 服务启动中...\n\n正在等待就绪, 请稍候");
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
                            error("内置服务器启动超时\n\n日志:\n" +
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
        busy("正在加载界面...");
        webView.loadUrl(embedded.serverUrl());
    }

    /** 点击覆盖层/状态文字重试 */
    private void setupRetry() {
        View.OnClickListener retry = v -> {
            if (embedded.isStarting()) {
                // 正在启动中, 不要打断, 重新等一轮
                pollHealth(120);
            } else {
                embedded.stop();
                embeddedConnect();
            }
        };
        startupOverlay.setOnClickListener(retry);
        statusView.setOnClickListener(retry);
    }

    /** 文件选择器结果回传: 单选/多选均支持, 取消则返回 null */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILECHOOSER_RESULT_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        pendingFileChooser = false;
        if (filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // ------------------------------------------------------------------
    // 所有文件访问 (写固定项目目录 /sdcard/opencode 需要) — 启动时弹窗授权
    // ------------------------------------------------------------------

    /** 未授予"所有文件访问"权限时, 首次启动弹窗引导授权; 拒绝则回退 app 专属目录 */
    private void maybePromptAllFilesAccess() {
        if (Environment.isExternalStorageManager()) return;
        new AlertDialog.Builder(this)
                .setTitle("使用公开项目目录")
                .setMessage("项目目录固定为 /sdcard/opencode (卸载 App 不丢失)。\n\n" +
                        "写该目录需要「所有文件访问」权限, 请前往系统设置授予。\n\n" +
                        "若暂不授权, 将使用 App 专属目录 (卸载会删除)。")
                .setCancelable(true)
                .setPositiveButton("去授权", (d, w) -> requestAllFilesAccess())
                .setNegativeButton("暂不", (d, w) -> d.dismiss())
                .show();
    }

    private void requestAllFilesAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ------------------------------------------------------------------
    // 息屏后台运行: 前台服务保活 + 唤醒锁 + 电池优化豁免
    // ------------------------------------------------------------------

    /** 启动前台保活服务 (通知栏常驻 + PARTIAL_WAKE_LOCK 息屏不休眠) */
    private void ensureServerService() {
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            // 通知权限被拒等, 忽略 (服务仍能跑, 只是看不到通知)
        }
    }

    /** Android 13+ 通知需要运行时权限, 否则前台服务通知不显示 */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }
    }

    /** 关闭系统对应用的电池优化, 防止息屏后 Doze 中断长任务 (只引导一次) */
    private void maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (prefs.getBoolean("battery_opt_prompted", false)) return;
        prefs.edit().putBoolean("battery_opt_prompted", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("允许后台运行")
                .setMessage("为保证息屏后长任务不被系统中断, 请对本应用关闭电池优化。\n\n" +
                        "小米/OPPO 等手机还可在「设置 → 应用 → 电池/自启动」允许后台运行, 效果更好。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("暂不", (d, w) -> d.dismiss())
                .show();
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
            embedded.noteClientActivity();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        // 不暂停 WebView: 保留页面 DOM/滚动位置, 后台回来不闪白、不整页重载。
        // 后台耗电由 ServerService 看护兜底 (空闲释放唤醒锁 / 长时间空闲自动停止 server)。
        wasBackgrounded = true;
        lastPauseElapsed = SystemClock.elapsedRealtime();
        lastPauseWall = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        embedded.noteClientActivity();
        // 从"所有文件访问"设置页返回: 授权后重启服务以生效公开项目目录
        boolean granted = Environment.isExternalStorageManager();
        if (!hadAllFilesAccess && granted && embedded.isRunning()) {
            embedded.stop();
            embeddedConnect();
            hadAllFilesAccess = granted;
            wasBackgrounded = false;
            return;
        }
        hadAllFilesAccess = granted;
        if (wasBackgrounded) {
            wasBackgrounded = false;
            // 文件选择器返回 / 快速切回: 无需任何处理
            if (pendingFileChooser ||
                    SystemClock.elapsedRealtime() - lastPauseElapsed < BG_REFRESH_THRESHOLD_MS) {
                return;
            }
            if (embedded.isRunning()) {
                resyncAfterBackground();
            } else {
                // server 已被空闲看护停止, 重新拉起 (覆盖层会显示启动/加载进度)
                embeddedConnect();
            }
        }
    }

    /**
     * 后台回到前台后与 server 对齐。
     *
     * 关键: 对齐判断必须基于 st.pending (存在未完成消息), 不能只看 st.replying。
     * replying = pending 且 20 分钟内有进展; 后台被冻结/中断的回复进展时间戳早已变旧,
     * replying=false 而会话也没有新完成的消息 → 旧版两个分支都不命中, 什么都不做,
     * 页面就停在断掉的 SSE 订阅上永远显示"思考中"。
     *
     * 对齐动作:
     *   - 无未完成消息但离开期间有新完成的结果 → 直接 reload 拉取;
     *   - 有未完成消息 → 先唤醒页面自身重连 (visibilitychange/focus), 5s 后页面仍
     *     无任何变化视为卡死, 再区分处理:
     *       · 回复仍活跃 (replying) → reload 重新订阅 SSE (不打断 server 端回复);
     *       · 疑似孤儿 (未完成但已无进展) → 先做 2s CPU 快检:
     *           CPU 在烧 = 活着的慢任务, 只 reload;
     *           CPU 为零 = 冻结的孤儿, 调 abort 把消息落定为已中止再 reload
     *           (否则重载后页面照样把它渲染成"思考中", 永远卡住)。
     *
     * HTTP 探测必须放子线程: 主线程上 HttpURLConnection 会抛 NetworkOnMainThreadException,
     * 之前直接在 onResume 里调用, 异常被 catch 吞掉后恒等于"没在回复", 于是每次回来都整页
     * reload (既闪白又让回复中分支成了死代码)。
     */
    private void resyncAfterBackground() {
        final long pausedAtWall = lastPauseWall;
        new Thread(() -> {
            ServerManager.Status st = embedded.status();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || webView == null) return;
                if (!st.pending) {
                    // 没有未完成消息: 离开期间有已完成的新结果才需要刷新, 否则页面本来就是最新的
                    if (st.sessionUpdated > 0 && st.sessionUpdated > pausedAtWall) {
                        webView.reload();
                    }
                    return;
                }
                // 有未完成消息: 先唤醒页面自身的重连逻辑
                webView.evaluateJavascript(
                        "(function(){try{" +
                        "window.__ocBodyLen=document.body?document.body.innerText.length:-1;" +
                        "document.dispatchEvent(new Event('visibilitychange'));" +
                        "window.dispatchEvent(new Event('focus'));" +
                        "}catch(e){}})()", null);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isFinishing() || isDestroyed() || webView == null) return;
                    webView.evaluateJavascript(
                            "(function(){try{" +
                            "return window.__ocBodyLen===document.body.innerText.length" +
                            "}catch(e){return false}})()",
                            v -> {
                                if (isFinishing() || isDestroyed() || webView == null) return;
                                if (!"true".equals(v)) return; // 页面在自己恢复/流式输出中, 不打扰
                                resolveStalledPending(st);
                            });
                }, 5000);
            });
        }, "opencode-resync").start();
    }

    /** 页面卡死在有未完成消息的状态: 按 server 侧状态决定 reload 还是 abort+reload */
    private void resolveStalledPending(ServerManager.Status st) {
        if (st.replying) {
            // 回复活跃: 重订阅 SSE 即可, 绝不能 abort (会杀掉正在跑的任务)
            webView.reload();
            return;
        }
        // 未完成但已无进展: 用 2s CPU 快检区分"活着的慢任务"和"冻结的孤儿"
        new Thread(() -> {
            boolean cpuBusy = embedded.cpuActiveQuickCheck();
            if (cpuBusy) {
                Log.i("MainActivity", "resume: pending stalled but cpu busy, reload only");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || webView == null) return;
                    webView.reload();
                });
                return;
            }
            Log.i("MainActivity", "resume: orphan reply detected, abort + reload");
            if (!st.sessionId.isEmpty()) embedded.abortSession(st.sessionId);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || webView == null) return;
                webView.reload();
            });
        }, "opencode-orphan").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        polling.set(false);
        handler.removeCallbacksAndMessages(null);
        // 不在这里停 server: 息屏/退出 App 后由 ServerService 保活, 长任务不中断;
        // 用户可通过通知栏"停止"或重新打开 App 管理
    }
}