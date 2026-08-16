package com.opencode.android;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌 opencode server 管理器。
 *
 * opencode(linux-arm64-musl) 二进制打包在 APK assets/opencode/ 里,
 * 首次启动时流式解压到 app 私有目录, 然后作为子进程拉起:
 *   opencode serve --port 18888 --hostname 127.0.0.1
 *
 * HOME / XDG_* 全部指向 app 私有目录, 配置与登录态随 APP 持久化。
 * APP 退出时 (onDestroy) 调用 stop() 杀掉子进程。
 */
public class ServerManager {

    private static final String TAG = "OpenCodeServer";
    private static final int PORT = 18888;
    private static final String ASSET_BIN = "opencode/opencode";
    private static final String ASSET_VERSION = "opencode/version.txt";

    private static volatile ServerManager instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private volatile Process process;
    private volatile boolean stopRequested = false;
    private File logFile;

    public static synchronized ServerManager get(Context ctx) {
        if (instance == null) {
            instance = new ServerManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private ServerManager(Context ctx) {
        this.ctx = ctx;
    }

    /** 只支持 arm64 (几乎覆盖所有现代手机/平板) */
    public boolean isSupported() {
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) return false;
        String abi = Build.SUPPORTED_ABIS[0];
        return abi.equals("arm64-v8a") || abi.equals("aarch64");
    }

    public boolean hasEmbeddedBinary() {
        try {
            ctx.getAssets().open(ASSET_BIN).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public boolean isStarting() {
        return starting.get();
    }

    public String serverUrl() {
        return "http://127.0.0.1:" + PORT;
    }

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /** 异步启动 server, 结果回到主线程 */
    public void start(final Callback cb) {
        if (isRunning()) {
            cb.onResult(true, "server 已在运行");
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            cb.onResult(false, "server 正在启动中");
            return;
        }
        stopRequested = false;
        new Thread(() -> {
            String err = null;
            try {
                File bin = ensureBinary();
                startProcess(bin);
                if (stopRequested) {
                    Process pp = process;
                    process = null;
                    if (pp != null) pp.destroy();
                }
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.e(TAG, "start failed", e);
            }
            starting.set(false);
            final String msg = err;
            main.post(() -> cb.onResult(msg == null, msg == null ? "已启动" : "启动失败: " + msg));
        }).start();
    }

    /** 停止子进程 (不阻塞等待) */
    public void stop() {
        stopRequested = true;
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            Log.i(TAG, "server process stopped");
        }
    }

    /** 返回日志文件尾部, 用于失败排查 */
    public String getLogTail(int maxBytes) {
        File f = logFile;
        if (f == null || !f.exists()) return "(无日志)";
        try (FileInputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - maxBytes);
            in.skip(skip);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(日志读取失败)";
        }
    }

    /** 把 assets 里的二进制解压到 filesDir (版本变化时覆盖) */
    private File ensureBinary() throws IOException {
        File bin = new File(ctx.getFilesDir(), "opencode/bin/opencode");
        File verFile = new File(ctx.getFilesDir(), "opencode/.version");
        String assetVersion = readText(ctx.getAssets().open(ASSET_VERSION));
        String localVersion = verFile.exists() ? readText(new FileInputStream(verFile)) : "";
        if (bin.exists() && localVersion.equals(assetVersion)) {
            return bin;
        }
        if (bin.getParentFile() != null) bin.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(ASSET_BIN);
             OutputStream out = new FileOutputStream(bin)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!bin.setExecutable(true, false)) {
            throw new IOException("无法设置可执行权限: " + bin);
        }
        try (OutputStream out = new FileOutputStream(verFile)) {
            out.write(assetVersion.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "binary extracted, version=" + assetVersion + ", size=" + bin.length());
        return bin;
    }

    private void startProcess(File bin) throws IOException {
        File root = new File(ctx.getFilesDir(), "opencode");
        File home = new File(root, "home");
        File cfg = new File(root, "config");
        File data = new File(root, "data");
        File cache = new File(root, "cache");
        home.mkdirs();
        cfg.mkdirs();
        data.mkdirs();
        cache.mkdirs();
        logFile = new File(root, "server.log");

        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(),
                "serve",
                "--port", String.valueOf(PORT),
                "--hostname", "127.0.0.1");
        pb.redirectErrorStream(true);
        pb.directory(bin.getParentFile());
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("XDG_CONFIG_HOME", cfg.getAbsolutePath());
        pb.environment().put("XDG_DATA_HOME", data.getAbsolutePath());
        pb.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
        pb.environment().put("TERM", "xterm-256color");

        process = pb.start();
        final Process proc = process;
        Log.i(TAG, "server process started");

        Thread logThread = new Thread(() -> {
            try (InputStream in = proc.getInputStream();
                 PrintStream ps = new PrintStream(new FileOutputStream(logFile, true), true)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) ps.write(buf, 0, n);
            } catch (IOException ignored) {
            }
        }, "opencode-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private static String readText(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}