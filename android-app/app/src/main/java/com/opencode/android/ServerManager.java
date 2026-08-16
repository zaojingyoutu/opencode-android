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
 * opencode (linux-arm64-musl) 是动态链接 musl 的二进制, 需要:
 *   - ld-musl-aarch64.so.1   (musl loader, interp 已 patchelf 指向固定路径)
 *   - libgcc_s.so.1 / libstdc++.so.6  (C/C++ 运行时)
 * 这些文件打包在 APK assets/opencode/ 里, 首次启动提取到
 *   /data/user/0/<pkg>/files/opencode/{bin,lib}
 * (interp 写死该路径, 因此不能换位置)。
 *
 * 启动: opencode serve --port 18888 --hostname 127.0.0.1
 * 环境: HOME/XDG_CONFIG_HOME/XDG_DATA_HOME/XDG_CACHE_HOME/TMPDIR -> app 私有目录,
 *       LD_LIBRARY_PATH -> lib 目录。
 * APP 退出时 (onDestroy) 调用 stop() 杀掉子进程。
 */
public class ServerManager {

    private static final String TAG = "OpenCodeServer";
    private static final int PORT = 18888;
    private static final String ASSET_VERSION = "opencode/version.txt";
    private static final String[] ASSET_LIBS = {
            "opencode/lib/ld-musl-aarch64.so.1",
            "opencode/lib/libgcc_s.so.1",
            "opencode/lib/libstdc++.so.6",
            "opencode/lib/ca-certificates.crt",
    };

    private static volatile ServerManager instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private volatile Process process;
    private volatile boolean stopRequested = false;
    private File logFile;
    private final DnsProxy dnsProxy = new DnsProxy();

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

    /** APK 内是否包含内置运行环境 (nativeLibraryDir 有二进制) */
    public boolean hasEmbeddedBinary() {
        return binFile().exists();
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

    /** 启动阶段进度回调 (运行在后台线程, 需要自行切主线程) */
    public interface Progress {
        void onProgress(String stage);
    }

    /** 异步启动 server, 结果回到主线程 */
    public void start(final Callback cb) {
        start(cb, null);
    }

    /** 异步启动 server, 结果回到主线程; progress 可选, 报告启动阶段 */
    public void start(final Callback cb, final Progress progress) {
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
                if (progress != null) {
                    progress.onProgress("正在准备运行环境...");
                }
                File bin = ensureRuntime();
                if (progress != null) {
                    progress.onProgress("正在启动 OpenCode 服务...");
                }
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
        dnsProxy.stop();
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

    /**
     * 数据根目录 (files/opencode, 存放配置/库/日志)。
     * loader 的 interp 路径写死在此, 不能换位置。
     */
    private File dataRoot() {
        return new File(ctx.getFilesDir(), "opencode");
    }

    /**
     * 二进制位置: nativeLibraryDir/libopencode.so。
     * 系统安装 APK 时解压 (app_lib_file 类型, SELinux 允许执行)。
     * 部分 ROM 禁止 app 执行 files/ (app_data_file) 下的文件 (error=13), 因此二进制不能放 files。
     */
    private File binFile() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libopencode.so");
    }

    /** 确保二进制 + 全部动态库就位 (版本变化时重新提取), 返回二进制路径 */
    private File ensureRuntime() throws IOException {
        File root = dataRoot();
        File bin = binFile();
        File libDir = new File(root, "lib");
        String assetVersion = readText(ctx.getAssets().open(ASSET_VERSION));
        File verFile = new File(root, ".version");
        String localVersion = verFile.exists() ? readText(new FileInputStream(verFile)) : "";

        boolean missing = !bin.exists() || !new File(libDir, "ld-musl-aarch64.so.1").exists()
                || !new File(libDir, "libgcc_s.so.1").exists()
                || !new File(libDir, "libstdc++.so.6").exists()
                || !new File(libDir, "ca-certificates.crt").exists();
        if (bin.exists() && !missing && localVersion.equals(assetVersion)) {
            return bin;
        }
        if (root.exists()) deleteRecursive(root);
        root.mkdirs();
        libDir.mkdirs();

        for (String asset : ASSET_LIBS) {
            extractAsset(asset, new File(libDir, asset.substring(asset.lastIndexOf('/') + 1)));
        }
        for (String asset : ASSET_LIBS) {
            setReadable(new File(libDir, asset.substring(asset.lastIndexOf('/') + 1)));
        }
        try (OutputStream out = new FileOutputStream(verFile)) {
            out.write(assetVersion.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "runtime extracted, version=" + assetVersion + ", bin=" + bin.length() + " bytes");
        return bin;
    }

    private void extractAsset(String asset, File dest) throws IOException {
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void setReadable(File f) throws IOException {
        f.setReadable(true, false);
        f.setWritable(true, false);
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    /** 写默认 opencode.jsonc 配置文件 (指定免费模型, 用户可在 Web UI 中改) */
    private void writeDefaultConfig(File cfgDir) throws IOException {
        File configFile = new File(cfgDir, "opencode/opencode.jsonc");
        configFile.getParentFile().mkdirs();
        if (configFile.exists()) {
            Log.i(TAG, "config already exists, skipping default config");
            return;
        }
        // 默认使用 opencode Zen 免费模型 (api.z.ai, 国内可直连, 无需 API Key)
        // 用户可在 Web UI 设置中添加其他 provider
        String config = "{\n" +
            "  \"model\": \"opencode-zen/deepseek-v4-flash-free\"\n" +
            "}\n";
        try (OutputStream out = new FileOutputStream(configFile)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "default config written: " + configFile.getAbsolutePath());
    }

    /** 写 resolv.conf 到 patch 后的路径 (musl 从那里读 nameserver) */
    private void writeResolvConf() {
        File f = new File(dataRoot(), "resolv.conf");
        StringBuilder sb = new StringBuilder();
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network[] nets = cm.getAllNetworks();
                for (android.net.Network n : nets) {
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        for (java.net.InetAddress dns : lp.getDnsServers()) {
                            String h = dns.getHostAddress();
                            if (h != null && !dns.isLoopbackAddress() && h.indexOf('%') < 0) {
                                sb.append("nameserver ").append(h).append('\n');
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sb.append("nameserver 223.5.5.5\n");
        sb.append("nameserver 8.8.8.8\n");
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "resolv.conf written:\n" + sb);
        } catch (IOException e) {
            Log.w(TAG, "cannot write resolv.conf", e);
        }
    }

    /** 清理上次残留的 server 进程 (通过 pid 文件; 同 uid 可直接 kill) */
    private static void killStaleServer(File root) {
        File pidFile = new File(root, "server.pid");
        if (!pidFile.exists()) return;
        String s = "";
        try (FileInputStream in = new FileInputStream(pidFile)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            if (n > 0) s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
        }
        if (s.isEmpty()) return;
        try {
            int pid = Integer.parseInt(s);
            if (pid > 1) {
                android.os.Process.killProcess(pid);
                Log.i(TAG, "killed stale server pid=" + pid);
            }
        } catch (Exception ignored) {
        }
        pidFile.delete();
    }

    private static void writePidFile(File root, int pid) {
        try (OutputStream out = new FileOutputStream(new File(root, "server.pid"))) {
            out.write(String.valueOf(pid).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cannot write pid file", e);
        }
    }

    /** Process.pid() 是 API 26+, 这里用反射兼容 API 24/25 */
    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private void startProcess(File bin) throws IOException {
        File root = dataRoot();
        File home = new File(root, "home");
        File cfg = new File(root, "config");
        File data = new File(root, "data");
        File cache = new File(root, "cache");
        File tmp = new File(root, "tmp");
        File libDir = new File(root, "lib");
        home.mkdirs();
        cfg.mkdirs();
        data.mkdirs();
        cache.mkdirs();
        tmp.mkdirs();
        logFile = new File(root, "server.log");

        // 写入默认 opencode 配置 (指定免费模型, 用户可在 Web UI 中改)
        writeDefaultConfig(cfg);

        // 清理上次残留的 server 进程 (APP 被系统杀时子进程会成孤儿继续占端口)
        killStaleServer(root);

        // musl 的 getaddrinfo 读不到 Android 的 /etc/resolv.conf (不存在),
        // 二进制已 patch 指向 files/opencode/resolv.conf, 这里写入真实 DNS 配置
        writeResolvConf();

        // musl/Bun 读不到 /etc/resolv.conf, 在 127.0.0.1:53 架 DNS 代理让 Bun 能解析域名
        dnsProxy.start();

        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(),
                "serve",
                "--port", String.valueOf(PORT),
                "--hostname", "127.0.0.1");
        pb.redirectErrorStream(true);
        // cwd 用 home 目录, opencode Web UI 里的文件浏览从用户根目录开始
        pb.directory(home);
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("XDG_CONFIG_HOME", cfg.getAbsolutePath());
        pb.environment().put("XDG_DATA_HOME", data.getAbsolutePath());
        pb.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        pb.environment().put("SSL_CERT_FILE", new File(libDir, "ca-certificates.crt").getAbsolutePath());
        pb.environment().put("TERM", "xterm-256color");

        process = pb.start();
        final Process proc = process;
        writePidFile(root, pidOf(proc));
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