package com.opencode.android;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * 内嵌 opencode server 管理器 (proot 容器模式)。
 *
 * 原理: 在 Android 上跑一个完整的 Alpine Linux (arm64) 容器:
 *   - proot (termux 编译, bionic 链接) 以系统调用拦截方式伪 chroot,
 *     放在 nativeLibraryDir (libproot.so), 极端 ROM 也允许执行;
 *   - Alpine rootfs 打包在 APK assets/opencode/rootfs.tar (纯 tar, 首次启动解压到
 *     files/opencode/rootfs (容器内二进制由 proot 的 loader 直接 mmap 加载, 只需读权限);
 *   - 启动命令:
 *       libproot.so -0 -r <rootfs> -b <projects>:/workspace -b <home>:/root \
 *                   -b <resolv.conf>:/etc/resolv.conf -b /dev -b /proc -b /sys \
 *                   -w /workspace --kill-on-exit /bin/sh -c \
 *                   "opencode serve --port 18888 --hostname 127.0.0.1"
 *   - 用户在 Web UI 终端里可 apk add nodejs python3 git gcc 等任意工具。
 * APP 退出时进程继续由前台服务 (ServerService) 保持运行; 用户从通知栏点"停止"
 * 或调用 stop() 才会杀掉 proot 进程 (--kill-on-exit 会带走全部子进程)。
 */
public class ServerManager {

    private static final String TAG = "OpenCodeServer";
    private static final int PORT = 18888;
    /** 未完成回复的"新鲜度"窗口: 超过此时长毫无进展的未完成消息视为疑似中断残留, 不再算回复中。
     *  取 20 分钟: 长时间静默的工具调用 (大文件下载/长测试) part 时间戳可以很久不更新,
     *  窗口太小会把活任务误判成孤儿 → 看护线程放锁 → CPU 休眠 → 任务被冻死 */
    private static final long REPLY_FRESH_MS = 20 * 60_000L;
    private static final String ASSET_VERSION = "opencode/version.txt";
    private static final String ASSET_ROOTFS = "opencode/rootfs.tar";
    private static final String[] ASSET_PROOT_LIBS = {
            "opencode/proot/libtalloc.so.2",
            "opencode/proot/libandroid-shmem.so",
    };

    private static volatile ServerManager instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private volatile Process process;
    private volatile boolean stopRequested = false;
    private File logFile;

    // 客户端(Web UI)最近活动时间戳: 供 ServerService 空闲看护判断"用户是否在用"
    private volatile long lastActivityElapsed = 0;
    private volatile long lastWriteWall = 0;

    public static synchronized ServerManager get(Context ctx) {
        if (instance == null) {
            instance = new ServerManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private ServerManager(Context ctx) {
        this.ctx = ctx;
    }

    /** 只支持 arm64 (覆盖绝大多数现代手机/平板) */
    public boolean isSupported() {
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) return false;
        String abi = Build.SUPPORTED_ABIS[0];
        return abi.equals("arm64-v8a") || abi.equals("aarch64");
    }

    /** APK 内是否包含内置运行环境 (nativeLibraryDir 有 proot) */
    public boolean hasEmbeddedBinary() {
        return prootFile().exists();
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public boolean isStarting() {
        return starting.get();
    }

    /** 记录一次客户端活动 (打开/操作/页面加载), 供空闲看护用; 写入 prefs 做了节流 */
    public void noteClientActivity() {
        lastActivityElapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        if (wall - lastWriteWall < 60_000) return;
        lastWriteWall = wall;
        ctx.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE)
                .edit().putLong("last_activity_wall", wall).apply();
    }

    /** 距上次客户端活动是否在窗口 ms 内 (内存时间戳, 进程被杀后失效) */
    public boolean clientActiveWithinMs(long ms) {
        return lastActivityElapsed != 0
                && SystemClock.elapsedRealtime() - lastActivityElapsed < ms;
    }

    /** 距上次客户端活动是否在窗口 ms 内 (持久化时间戳, 跨进程重启可用) */
    public boolean recentlyUsed(long windowMs) {
        long last = ctx.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE)
                .getLong("last_activity_wall", 0);
        return last != 0 && System.currentTimeMillis() - last < windowMs;
    }

    /** proot 进程 pid, 未运行返回 0 */
    public int pid() {
        Process p = process;
        return p != null ? pidOf(p) : 0;
    }

    /**
     * server 侧状态快照。
     */
    public static final class Status {
        /** 最新会话的更新时间戳 (ms 墙上时间); server 不可用为 -1 */
        public final long sessionUpdated;
        /** 是否有进行中的回复 (思考/生成中) = pending 且最近仍有进展 */
        public final boolean replying;
        /** 是否存在未完成消息 (无 completed 且无 error), 不论新鲜度。
         *  后台被冻结/中断的回复进展时间戳会变旧 (replying=false) 但仍是 pending,
         *  恢复前台的对齐逻辑必须看它, 否则页面会永远卡在"思考中" */
        public final boolean pending;
        /** 最新一条消息是否以错误收场 (供完成通知区分成功/失败) */
        public final boolean error;
        /** 最新会话 id, 用于 abort 孤儿回复; 空串表示未知 */
        public final String sessionId;

        Status(long sessionUpdated, String sessionId, boolean pending, boolean replying,
                boolean error) {
            this.sessionUpdated = sessionUpdated;
            this.sessionId = sessionId;
            this.pending = pending;
            this.replying = replying;
            this.error = error;
        }
    }

    /**
     * 查询 server 侧状态, <b>必须在子线程调用</b> (主线程会抛 NetworkOnMainThreadException)。
     *
     * 回复中判定: 最新一条消息没有 time.completed、没有 error, 且最近仍有进展。
     * 两个坑都是实测踩出来的:
     * 1) 必须叠加"最近有进展": server 被杀/回复被打断会永久留下没有 completed 的孤儿消息
     *    (实测 191 条 assistant 里 3 条如此, 最老的 14 小时前), 只看 completed 会让看护线程
     *    永远认为"在回复", 唤醒锁再也放不掉, 比不优化还耗电。
     * 2) 进展时间不能用会话的 time.updated: 实测它基本等于上一条消息的 completed 时刻;
     *    真正的流式进展在 part 上 (text part 的 time.start/end, tool part 的 state.time.start/end)。
     */
    public Status status() {
        try {
            org.json.JSONArray sessions = new org.json.JSONArray(httpGet(serverUrl() + "/session"));
            org.json.JSONObject latest = null;
            long updated = -1;
            for (int i = 0; i < sessions.length(); i++) {
                org.json.JSONObject s = sessions.optJSONObject(i);
                if (s == null) continue;
                org.json.JSONObject t = s.optJSONObject("time");
                long up = t != null ? t.optLong("updated", -1) : -1;
                if (up > updated) {
                    updated = up;
                    latest = s;
                }
            }
            if (latest == null) return new Status(-1, "", false, false, false);
            String sid = latest.optString("id", "");
            if (sid.isEmpty()) return new Status(updated, "", false, false, false);
            // 实测该端点按时间升序返回且 limit=1 拿到的是最老一条 (任何会话第一条都是
            // 已完成的 user 消息 → replying 永远 false, 看护/恢复逻辑全部失效)。
            // 必须取数组末尾才是最新消息; 大会话 (225 条实测 ~1MB) 每 60s 拉一次可接受,
            // 若嫌大可在 server 侧支持降序 limit 时再改回。
            org.json.JSONArray msgs = new org.json.JSONArray(
                    httpGet(serverUrl() + "/session/" + sid + "/message"));
            org.json.JSONObject msg = msgs.length() > 0
                    ? msgs.optJSONObject(msgs.length() - 1) : null;
            org.json.JSONObject info = msg != null ? msg.optJSONObject("info") : null;
            if (info == null) return new Status(updated, sid, false, false, false);
            org.json.JSONObject time = info.optJSONObject("time");
            if (time != null && time.has("completed")) return new Status(updated, sid, false, false, false);
            if (!info.isNull("error")) return new Status(updated, sid, false, false, true);
            long progress = Math.max(time != null ? time.optLong("created", -1) : -1,
                    latestPartTs(msg.optJSONArray("parts")));
            boolean fresh = progress > 0 && System.currentTimeMillis() - progress < REPLY_FRESH_MS;
            return new Status(updated, sid, true, fresh, false);
        } catch (Exception e) {
            return new Status(-1, "", false, false, false);
        }
    }

    /** parts 里最后一次进展时间: text part 取 time.start/end, tool part 取 state.time.start/end */
    private static long latestPartTs(org.json.JSONArray parts) {
        long max = -1;
        if (parts == null) return max;
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            max = Math.max(max, tsOf(p.optJSONObject("time")));
            org.json.JSONObject state = p.optJSONObject("state");
            if (state != null) max = Math.max(max, tsOf(state.optJSONObject("time")));
        }
        return max;
    }

    private static long tsOf(org.json.JSONObject time) {
        return time == null ? -1 : Math.max(time.optLong("start", -1), time.optLong("end", -1));
    }

    /** 中止会话当前回复 (孤儿消息收尾用), <b>子线程调用</b>; 尽力而为, 失败只记日志 */
    public void abortSession(String sid) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    serverUrl() + "/session/" + sid + "/abort").openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                Log.i(TAG, "abort " + sid + ": http " + code);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "abort failed: " + e);
        }
    }

    /** 2 秒内容器进程树 CPU 是否明显在跑。
     *  用于区分"活着的慢任务" (CPU 在烧, 不能 abort) 和"冻结的孤儿回复" (CPU 为零, abort 落定) */
    public boolean cpuActiveQuickCheck() {
        long t1 = totalCpuTicks();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            return false;
        }
        long t2 = totalCpuTicks();
        // 100 tick = 1s CPU (100Hz jiffies), 2s 墙钟内 ≈ 50% 单核
        return t2 - t1 >= 100;
    }

    /** GET 指定 URL 返回响应体字符串, 失败抛异常 */
    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            // 非 200 时 getInputStream() 会抛异常, 必须走 getErrorStream 才能读完并正常断开
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in == null ? "" : readText(in);
            if (code != 200) throw new IOException("http " + code);
            return body;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 返回 proot 进程树的累计 CPU 时钟数 (jiffies)。
     * 遍历 /proc 按 ppid 构建进程树求和 (proot 环境下 /proc/<pid>/children 不可读,
     * 只有该文件时只能统计到 proot 自身, 会漏掉容器内真正耗 CPU 的子进程)。
     */
    public long totalCpuTicks() {
        int pid = pid();
        if (pid <= 0) return 0;
        // 第一遍: 收集所有 pid 的 ppid 与 CPU 累计值
        java.util.HashMap<Integer, java.util.List<Integer>> byParent = new java.util.HashMap<>();
        java.util.HashMap<Integer, Long> ticks = new java.util.HashMap<>();
        File proc = new File("/proc");
        String[] entries = proc.list();
        if (entries == null) return 0;
        for (String e : entries) {
            int p;
            try {
                p = Integer.parseInt(e);
            } catch (NumberFormatException nfe) {
                continue;
            }
            String[] f = statFields(p);
            if (f == null) continue;
            int ppid = Integer.parseInt(f[1]);
            java.util.List<Integer> kids = byParent.get(ppid);
            if (kids == null) {
                kids = new java.util.ArrayList<>();
                byParent.put(ppid, kids);
            }
            kids.add(p);
            ticks.put(p, Long.parseLong(f[11]) + Long.parseLong(f[12]));
        }
        // 第二遍: 从 proot pid 出发沿父链收集所有后代并求和
        long total = 0;
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        stack.push(pid);
        while (!stack.isEmpty()) {
            int p = stack.pop();
            if (!seen.add(p)) continue;
            Long t = ticks.get(p);
            if (t != null) total += t;
            java.util.List<Integer> kids = byParent.get(p);
            if (kids != null) {
                for (int k : kids) stack.push(k);
            }
        }
        return total;
    }

    /** 读 /proc/<pid>/stat 切分字段 (跳过 comm 因可能含空格/括号), 失败返回 null */
    private static String[] statFields(int pid) {
        try {
            String stat = readProc(new File("/proc", pid + "/stat"));
            if (stat == null) return null;
            int close = stat.lastIndexOf(')');
            if (close < 0) return null;
            String[] f = stat.substring(close + 1).trim().split("\\s+");
            // f[0]=state(字段3), f[1]=ppid(字段4), f[11]=utime(字段14), f[12]=stime(字段15)
            return f.length >= 13 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readProc(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
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
                File bin = ensureRuntime(progress);
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

    /** 数据根目录 (files/opencode, 存放 rootfs/库/日志) */
    private File dataRoot() {
        return new File(ctx.getFilesDir(), "opencode");
    }

    /** proot 位置: nativeLibraryDir/libproot.so (app_lib_file, SELinux 允许执行) */
    private File prootFile() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    /**
     * 确保 proot 依赖库 + rootfs 就位 (版本变化时重新解压), 返回 proot 二进制路径。
     */
    private File ensureRuntime(Progress progress) throws IOException {
        File root = dataRoot();
        File prootDir = new File(root, "proot");
        File rootfs = new File(root, "rootfs");

        // proot 依赖的 termux 共享库 (libtalloc/libandroid-shmem) → files/opencode/proot
        // proot 是 bionic 链接的, 启动时通过 LD_LIBRARY_PATH 找到它们
        prootDir.mkdirs();
        for (String asset : ASSET_PROOT_LIBS) {
            File f = new File(prootDir, asset.substring(asset.lastIndexOf('/') + 1));
            if (!f.exists()) {
                extractAsset(asset, f);
                setReadable(f);
            }
        }

        String assetVersion = readText(ctx.getAssets().open(ASSET_VERSION));
        File verFile = new File(rootfs, ".opencode-version");
        String localVersion = verFile.exists() ? readText(new FileInputStream(verFile)) : "";
        boolean upToDate = new File(rootfs, "usr/local/bin/opencode").exists()
                && localVersion.equals(assetVersion);
        if (!upToDate) {
            if (progress != null) {
                progress.onProgress("正在解压内置运行环境 (首次约需 30 秒)...");
            }
            if (rootfs.exists()) deleteRecursive(rootfs);
            rootfs.mkdirs();
            extractTarGz(ctx.getAssets().open(ASSET_ROOTFS), rootfs);
            try (OutputStream out = new FileOutputStream(verFile)) {
                out.write(assetVersion.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "rootfs extracted, version=" + assetVersion);
        }
        return prootFile();
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
        // 默认使用 opencode 内置免费模型 (models.dev 提供, 无需 API Key)
        // 用户可在 Web UI 设置中添加其他 provider。
        // 注意: 免费模型会随 Zen 目录调整下架 (deepseek-v4-flash-free 已下架, 实测报
        // ProviderModelNotFoundError), 若再次失效请按 server 日志提示换新模型名
        String config = "{\n" +
            "  \"model\": \"opencode/hy3-free\"\n" +
            "}\n";
        try (OutputStream out = new FileOutputStream(configFile)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "default config written: " + configFile.getAbsolutePath());
    }

    /** 写 resolv.conf, 通过 proot -b 绑定到容器 /etc/resolv.conf (musl 的 getaddrinfo 从这里读) */
    private void writeResolvConf() {
        File f = new File(dataRoot(), "resolv.conf");
        // musl 的 getaddrinfo 只读 resolv.conf 里的前 3 个 nameserver (MAXNS=3)。
        // 系统 DNS 常常把不可用的 IPv6 地址排在最前, 导致解析完全失败;
        // 因此优先放可靠公共 DNS, 再补系统 IPv4 DNS, 最多 3 个。
        java.util.List<String> servers = new java.util.ArrayList<>();
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
                            // 只要 IPv4 (musl 前 3 个里混入不可达 IPv6 会直接解析失败)
                            if (h != null && !dns.isLoopbackAddress() && h.indexOf('%') < 0
                                    && h.indexOf(':') < 0) {
                                servers.add(h);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{"223.5.5.5", "114.114.114.114", "8.8.8.8"}) {
            if (countNameservers(sb) >= 3) break;
            sb.append("nameserver ").append(s).append('\n');
        }
        for (String s : servers) {
            if (countNameservers(sb) >= 3) break;
            String line = "nameserver " + s + "\n";
            if (sb.indexOf(line) < 0) {
                sb.append(line);
            }
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "resolv.conf written:\n" + sb);
        } catch (IOException e) {
            Log.w(TAG, "cannot write resolv.conf", e);
        }
    }

    private static int countNameservers(StringBuilder sb) {
        int n = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 清理上次残留的 proot 进程 (通过 pid 文件; 同 uid 可直接 kill) */
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
                Log.i(TAG, "killed stale proot pid=" + pid);
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

    /** 项目目录: 固定 /sdcard/opencode (卸载不丢, 文件管理器可见);
     *  未授予"所有文件访问"权限或创建失败时回退 app 专属外部目录 (卸载会删) */
    private File projectsDir() {
        File pub = new File(Environment.getExternalStorageDirectory(), "opencode");
        if ((pub.exists() || pub.mkdirs()) && pub.isDirectory()) {
            seedReadme(pub);
            return pub;
        }
        File appExternal = ctx.getExternalFilesDir(null);
        if (appExternal != null) appExternal.mkdirs();
        return new File(appExternal != null ? appExternal : dataRoot(), "Projects");
    }

    /** 首次使用在公开目录放一个说明文件, 提示用户项目都放这里 */
    private static void seedReadme(File dir) {
        File readme = new File(dir, "README.md");
        if (readme.exists()) return;
        try (OutputStream out = new FileOutputStream(readme)) {
            out.write(("# OpenCode Projects\n\n"
                    + "该目录是 OpenCode 内置 Linux 环境的工作目录 (/workspace)。\n"
                    + "在此新建/克隆的代码项目、配置都会保存在这里, 卸载 App 也不会丢失。\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private void startProcess(File bin) throws IOException {
        File root = dataRoot();
        File rootfs = new File(root, "rootfs");
        File prootDir = new File(root, "proot");
        // HOME 指向私有目录: 安全存放 opencode 的登录态、API Key、模型配置
        // (容器内可见为 /root)
        File home = new File(ctx.getFilesDir(), "home");
        // 项目/代码目录 → 容器内 /workspace。
        // 固定为公开目录 /sdcard/opencode (文件管理器可见, 卸载不丢);
        // 未授予"所有文件访问"权限或创建失败时回退 app 专属外部目录 (卸载会删)。
        File projects = projectsDir();
        projects.mkdirs();
        home.mkdirs();
        logFile = new File(root, "server.log");

        // 写入默认 opencode 配置 (容器内 XDG_CONFIG_HOME=/root/.config)
        writeDefaultConfig(new File(home, ".config"));

        // 清理上次残留的 proot 进程 (APP 被系统杀时子进程会成孤儿继续占端口)
        killStaleServer(root);

        // 写入真实 DNS 配置, 绑定到容器 /etc/resolv.conf
        writeResolvConf();

        File nativeLibDir = bin.getParentFile();
        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(),
                "-0",                                   // 容器内所有文件看起来属主为 root
                "-r", rootfs.getAbsolutePath(),
                "-b", projects.getAbsolutePath() + ":/workspace",
                "-b", home.getAbsolutePath() + ":/root",
                "-b", new File(root, "resolv.conf").getAbsolutePath() + ":/etc/resolv.conf",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/workspace",
                "--kill-on-exit",
                "/bin/sh", "-c",
                "opencode serve --port " + PORT + " --hostname 127.0.0.1");
        pb.redirectErrorStream(true);
        // proot 的 -w 会覆盖 cwd, 这里不设也无妨
        pb.directory(projects);

        Map<String, String> env = pb.environment();
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("HOME", "/root");
        env.put("XDG_CONFIG_HOME", "/root/.config");
        env.put("XDG_DATA_HOME", "/root/.local/share");
        env.put("XDG_CACHE_HOME", "/root/.cache");
        env.put("TMPDIR", "/tmp");
        env.put("TERM", "xterm-256color");
        // proot 需要可写临时目录 (f2fs 探测/glue rootfs)。PROOT_TMP_DIR 是宿主路径,
        // 默认编译进 termux 的路径不存在, 覆盖为 app 私有可写目录
        File prootTmp = new File(prootDir, "tmp");
        prootTmp.mkdirs();
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        env.put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt");
        // proot 是 bionic 链接的: 从私有目录加载 libtalloc/libandroid-shmem
        env.put("LD_LIBRARY_PATH", prootDir.getAbsolutePath());
        // termux 编译的 proot 写死了 loader 路径 (/data/data/com.termux/...),
        // 用 PROOT_LOADER / PROOT_LOADER_32 覆盖为 nativeLibraryDir 里的 loader
        // (opencode/容器内二进制都经由 loader 加载, 只需读权限)
        env.put("PROOT_LOADER", new File(nativeLibDir, "libproot-loader.so").getAbsolutePath());
        env.put("PROOT_LOADER_32", new File(nativeLibDir, "libproot-loader32.so").getAbsolutePath());

        process = pb.start();
        final Process proc = process;
        writePidFile(root, pidOf(proc));
        Log.i(TAG, "proot started: " + bin.getAbsolutePath());

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

    // ------------------------------------------------------------------
    // 极简 tar 解压 (支持 ustar/GNU longname/pax, 符号链接/硬链接)
    // ------------------------------------------------------------------

    private static void extractTarGz(InputStream gzIn, File destDir) throws IOException {
        // 兼容纯 tar 与 gzip tar: 探测 gzip magic (0x1f 0x8b)
        BufferedInputStream buffered = new BufferedInputStream(gzIn, 64 * 1024);
        buffered.mark(2);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        InputStream in = (b0 == 0x1f && b1 == 0x8b)
                ? new GZIPInputStream(buffered, 64 * 1024)
                : buffered;
        try (InputStream stream = in) {
            String pendingName = null;
            String pendingLink = null;
            List<TarEntry> deferredHardlinks = new ArrayList<>();
            TarEntry e;
            while ((e = readEntry(in)) != null) {
                switch (e.type) {
                    case 'L': // GNU longname
                        pendingName = e.data;
                        break;
                    case 'K': // GNU longlink
                        pendingLink = e.data;
                        break;
                    case 'x': // pax header
                        for (String rec : parsePax(e.data)) {
                            int eq = rec.indexOf('=');
                            if (eq < 0) continue;
                            String k = rec.substring(0, eq);
                            String v = rec.substring(eq + 1);
                            if ("path".equals(k)) pendingName = v;
                            else if ("linkpath".equals(k)) pendingLink = v;
                        }
                        break;
                    default: {
                        String name = pendingName != null ? pendingName : e.name;
                        String link = pendingLink != null ? pendingLink : e.linkname;
                        pendingName = null;
                        pendingLink = null;
                        name = stripDotSlash(name);
                        if (name.isEmpty() || name.startsWith("..")) break;
                        File target = new File(destDir, name);
                        if (e.type == '5' || e.type == 'D') { // dir
                            target.mkdirs();
                        } else if (e.type == '2') { // symlink
                            target.getParentFile().mkdirs();
                            target.delete();
                            try {
                                // android.system.Os 从 API 21 就有 (java.nio.file 要 API 26+)
                                android.system.Os.symlink(link, target.getAbsolutePath());
                            } catch (Exception ex) {
                                Log.w(TAG, "symlink failed: " + name, ex);
                            }
                        } else if (e.type == '1') { // hardlink
                            target.getParentFile().mkdirs();
                            File src = new File(destDir, stripDotSlash(link));
                            if (src.exists()) {
                                linkOrCopy(src, target);
                            } else {
                                e.name = name;
                                e.linkname = link;
                                deferredHardlinks.add(e);
                            }
                        } else { // regular file (0 / '\0')
                            target.getParentFile().mkdirs();
                            try (OutputStream out = new FileOutputStream(target)) {
                                copyN(in, out, e.size);
                            }
                            if ((e.mode & 0100) != 0) target.setExecutable(true, false);
                            setReadable(target);
                        }
                        skipPad(in, e.size);
                        break;
                    }
                }
            }
            // 处理目标位置在归档中靠后的硬链接
            for (TarEntry h : deferredHardlinks) {
                File target = new File(destDir, h.name);
                File src = new File(destDir, stripDotSlash(h.linkname));
                if (src.exists()) {
                    linkOrCopy(src, target);
                } else {
                    Log.w(TAG, "hardlink target missing: " + h.linkname);
                }
            }
        }
    }

    private static void linkOrCopy(File src, File target) throws IOException {
        target.delete();
        try {
            android.system.Os.link(src.getAbsolutePath(), target.getAbsolutePath());
        } catch (Exception e) {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(target)) {
                copyN(in, out, Long.MAX_VALUE);
            }
        }
    }

    private static String stripDotSlash(String s) {
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static List<String> parsePax(String data) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < data.length()) {
            int sp = data.indexOf(' ', i);
            if (sp < 0) break;
            try {
                int len = Integer.parseInt(data.substring(i, sp));
                if (len <= 0 || sp + len > data.length()) break;
                String rec = data.substring(sp + 1, sp + len - 1); // drop trailing \n
                out.add(rec);
                i = sp + len;
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    /** 读一个 512B tar 头 + 数据; 文件块类型时 e.data 为内容 (仅 'L'/'K'/'x'), 其余为空 */
    private static TarEntry readEntry(InputStream in) throws IOException {
        byte[] hdr = readExact(in, TarEntry.BLOCK);
        if (hdr == null || isAllZero(hdr)) return null;
        TarEntry e = new TarEntry();
        e.name = cstr(hdr, 0, 100);
        e.mode = (int) parseOctal(hdr, 100, 8);
        long size = parseOctal(hdr, 124, 12);
        e.type = (char) hdr[156];
        e.linkname = cstr(hdr, 157, 100);
        String magic = cstr(hdr, 257, 6);
        String prefix = cstr(hdr, 345, 155);
        if (!prefix.isEmpty()) e.name = prefix + "/" + e.name;
        e.size = size;
        e.data = null;
        if (e.type == 'L' || e.type == 'K' || e.type == 'x') {
            byte[] data = readExact(in, (int) size);
            e.data = data != null ? new String(data, StandardCharsets.UTF_8) : "";
            skipPad(in, size);
        }
        return e;
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return off == 0 ? null : buf;
            off += r;
        }
        return buf;
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (TarEntry.BLOCK - (size % TarEntry.BLOCK)) % TarEntry.BLOCK;
        while (pad > 0) {
            long r = in.skip(pad);
            if (r <= 0) {
                if (in.read() < 0) return;
                pad--;
            } else {
                pad -= r;
            }
        }
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) return;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            if (b[i] == 0) break;
            sb.append((char) (b[i] & 0xff));
        }
        return sb.toString();
    }

    /** 解析 tar 八进制数字 (兼容 base-256 大尺寸) */
    private static long parseOctal(byte[] b, int off, int len) {
        int end = off + len;
        if (end > b.length) end = b.length;
        // base-256 (GNU 大文件)
        if (off < end && (b[off] & 0x80) != 0) {
            long v = b[off] & 0x7f;
            for (int i = off + 1; i < end; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < end; i++) {
            byte c = b[i];
            if (c == ' ' || c == 0) break;
            if (c < '0' || c > '7') continue;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    private static final class TarEntry {
        static final int BLOCK = 512;
        String name;
        String linkname;
        long size;
        char type;
        int mode;
        String data;
    }
}
