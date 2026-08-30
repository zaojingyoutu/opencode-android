package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 前台服务: 让内置 Linux 环境在息屏/退出 App 后继续运行。
 * - 前台服务 + START_STICKY: 防止进程被系统回收 (长任务不中断)
 * - 常驻通知栏: 可见运行状态, 可一键"停止"
 *
 * 与 ServerManager 同进程, 不重复启动逻辑; 只负责"保活 + 通知 + 唤醒锁 + 空闲看护"。
 *
 * 空闲看护 (省电核心): 后台守护线程每 60s 采样一次容器 CPU 用量,
 *   - 忙碌/亮屏/近期有客户端活动 → 保持唤醒锁, server 继续跑 (长任务不中断);
 *   - 连续空闲 RELEASE_MINUTES 分钟 → 释放唤醒锁 (息屏后 CPU 可休眠);
 *   - 连续空闲 STOP_MINUTES 分钟 → 自动停止 server + 前台服务 (彻底省电);
 *   - 屏幕熄灭且无客户端活动时容器仍持续高 CPU 超过 FORCE_STOP_MINUTES →
 *     视为失控进程, 强制停止 (防止后台空转烧电); AI 回复推进期间除外。
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";
    public static final String ACTION_START = "com.opencode.android.action.START";
    public static final String ACTION_STOP = "com.opencode.android.action.STOP";
    /** 切换局域网访问开关 (重启 server 生效) */
    public static final String ACTION_LAN_TOGGLE = "com.opencode.android.action.LAN_TOGGLE";
    /** 通知栏"批准/拒绝"按钮: 直接回复权限请求, 不用进 App */
    public static final String ACTION_PERM_REPLY = "com.opencode.android.action.PERM_REPLY";
    private static final String CHANNEL_ID = "opencode_server";
    private static final int NOTIF_ID = 1001;
    /** 任务事件通知渠道 (回复完成/失败提醒): 用 v2 全新 ID。
     *  老渠道 opencode_task 在老版本里是 DEFAULT 级 (只进状态栏不弹横幅), 而 Android
     *  渠道一旦创建, 同 ID 删除重建在进程内不生效 (MIUI 实测 mOriginalImp 不变),
     *  只能换新 ID 保证创建即 HIGH */
    private static final String TASK_CHANNEL_ID = "opencode_task2";
    private static final int NOTIF_ID_TASK = 1002;
    /** 待批准提醒渠道 (AI 等用户批准工具调用): 高优先级横幅+声音, 与完成提醒分开便于分别管理 */
    private static final String PERMISSION_CHANNEL_ID = "opencode_permission";
    private static final int NOTIF_ID_PERMISSION = 1003;

    // ---- 空闲看护参数 ----
    private static final long WATCHDOG_PERIOD_MS = 60_000;        // 采样周期
    private static final long CLIENT_ACTIVE_MS = 10 * 60_000L;    // 客户端活动窗口
    private static final long SELF_HEAL_WINDOW_MS = 30 * 60_000L; // 进程被杀后自愈窗口
    private static final int BUSY_TICKS = 500;   // 60s 内 CPU ≥5s(≈8% 平均) 视为忙碌
    private static final int RELEASE_MINUTES = 3;   // 连续空闲 3 分钟 → 释放唤醒锁
    private static final int STOP_MINUTES = 30;     // 连续空闲 30 分钟 → 自动停止
    private static final int FORCE_STOP_MINUTES = 90; // 息屏无客户端仍持续高 CPU → 强制停止
    private static final int ORPHAN_ABORT_MINUTES = 15; // 持续无进展无 CPU → 判定孤儿并 abort 收尾

    private PowerManager.WakeLock wakeLock;
    private PowerManager pm;
    private ServerManager server;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Thread watchdog;
    private volatile boolean watchdogRunning;
    private int lastPid = -1;
    private long lastTicks = -1;
    private int idleMinutes = 0;
    private int busyMinutes = 0;
    /** 连续"有未完成消息但无进展无 CPU"的分钟数, 用于识别并收尾断流孤儿 */
    private int stalledMinutes = 0;

    // ---- 通知快看护 ----
    /** 通知快看护周期: 60s 空闲看护采样太粗, 快回复 (几秒~1 分钟) 会在两次采样之间完成,
     *  pending→completed 边沿永远看不到 → 完成通知漏发。10s 一轮快照, 延迟可接受 */
    private static final long NOTIFY_WATCH_PERIOD_MS = 10_000;
    private Thread notifyWatcher;
    private volatile boolean notifyWatcherRunning;
    /** 上一轮快照状态, 用于检测"回复结束"边沿 (发完成通知) */
    private ServerManager.Status lastStatus;
    /** 已提醒过的待批准请求 (会话id+工具摘要), 同一请求只弹一次横幅, 避免每轮看护重复轰炸 */
    private String permissionNotifiedKey = "";
    /** 连续探测 server 不响应的次数: opencode 这版权限待批会卡死事件循环, 超阈值自动重启自愈 */
    private int serverDownTicks = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        server = ServerManager.get(this);
        createChannel();
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:server");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        Log.i(TAG, "onStartCommand action=" + action);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LAN_TOGGLE.equals(intent.getAction())) {
            boolean on = !server.isLanEnabled();
            server.setLanEnabled(on);
            android.widget.Toast.makeText(this,
                    on ? "局域网访问已开启, 服务重启中..." : "已切换为仅本机访问, 服务重启中...",
                    android.widget.Toast.LENGTH_SHORT).show();
            Log.i(TAG, "lan toggled: " + on + ", restarting server");
            server.stop();
            if (!server.isRunning() && !server.isStarting()) {
                server.start((ok, msg) -> Log.i(TAG, "start result: ok=" + ok + " " + msg), null);
            }
            refreshNotification();
            return START_STICKY;
        }
        if (intent != null && ACTION_PERM_REPLY.equals(intent.getAction())) {
            handlePermissionReply(intent);
            return START_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
            // 记录当前内容, 让看护线程的周期刷新直接跳过 (避免重发触发 MIUI 重新响铃)
            lastNotifKey = notifContentKey();
            Log.i(TAG, "startForeground ok");
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e);
        }
        if (intent == null) {
            // 系统因内存回收重启服务: 仅用户近期用过时才自愈拉起, 否则不后台复活, 省电
            if (!server.recentlyUsed(SELF_HEAL_WINDOW_MS)) {
                Log.i(TAG, "sticky restart but no recent activity; stopping");
                stopAll();
                return START_NOT_STICKY;
            }
        }
        if (!server.isRunning() && !server.isStarting()) {
            Log.i(TAG, "starting server");
            server.start((ok, msg) -> Log.i(TAG, "start result: ok=" + ok + " " + msg), null);
        }
        startWatchdog();
        return START_STICKY;
    }

    private void startWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        lastPid = -1;
        lastTicks = -1;
        idleMinutes = 0;
        busyMinutes = 0;
        stalledMinutes = 0;
        watchdog = new Thread(this::watchdogLoop, "opencode-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        startNotifyWatcher();
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        if (watchdog != null) watchdog.interrupt();
        stopNotifyWatcher();
    }

    private void startNotifyWatcher() {
        if (notifyWatcherRunning) return;
        notifyWatcherRunning = true;
        lastStatus = null;
        permissionNotifiedKey = "";
        notifyWatcher = new Thread(this::notifyWatcherLoop, "opencode-notify");
        notifyWatcher.setDaemon(true);
        notifyWatcher.start();
        startSseSubscription();
        Log.i(TAG, "notify watcher started");
    }

    private void stopNotifyWatcher() {
        notifyWatcherRunning = false;
        if (notifyWatcher != null) notifyWatcher.interrupt();
        stopSseSubscription();
    }

    private void notifyWatcherLoop() {
        while (notifyWatcherRunning) {
            try {
                Thread.sleep(NOTIFY_WATCH_PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!notifyWatcherRunning) break;
            try {
                notifyTick();
            } catch (Exception e) {
                Log.w(TAG, "notifyTick error", e);
            }
        }
    }

    // ---- SSE 事件订阅: 事件到达瞬间发通知, 消除轮询窗口 ----
    // opencode 这版在权限待批时事件循环会卡死 (ServeError), 10s 轮询经常赶不上;
    // 而 Web UI 的审批弹窗正是 permission.asked 事件驱动的 — 弹窗能出现说明事件
    // 已送达, 订阅同一事件流就能在同一瞬间发出通知, 抢在 server 挂死之前
    private Thread sseThread;
    private volatile boolean sseRunning;
    /** 最近一次会话标题 (SSE 触发的通知用, 免再查一次 HTTP) */
    private volatile String lastSessionTitle = "";
    /** SSE 触发完成检测的节流: 流式期间事件很密, 别打爆 server */
    private volatile long lastSseCheckMs;

    private void startSseSubscription() {
        if (sseRunning) return;
        sseRunning = true;
        sseThread = new Thread(this::sseLoop, "opencode-sse");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void stopSseSubscription() {
        sseRunning = false;
        if (sseThread != null) sseThread.interrupt();
    }

    private void sseLoop() {
        while (sseRunning) {
            java.net.HttpURLConnection conn = null;
            try {
                if (!server.isRunning()) {
                    Thread.sleep(3000);
                    continue;
                }
                conn = (java.net.HttpURLConnection) new java.net.URL(
                        server.serverUrl() + "/event").openConnection();
                conn.setRequestProperty("Authorization", server.basicAuth());
                conn.setConnectTimeout(5000);
                // 15s 读超时: 事件流空闲时也算健康, 到点重连一次防半死连接
                conn.setReadTimeout(15_000);
                if (conn.getResponseCode() != 200) {
                    Thread.sleep(5000);
                    continue;
                }
                Log.i(TAG, "sse subscribed");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while (sseRunning && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        handleServerEvent(line.substring(5).trim());
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {
                // 连接断开/超时 → 下面重连
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (sseRunning) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /** 处理 server 事件: permission.asked 直接触发通知 (负载自带请求字段, 零 HTTP);
     *  message.updated 触发一次完成检测 (节流) */
    private void handleServerEvent(String json) {
        try {
            if (json.contains("permission.asked")) {
                org.json.JSONObject req = new org.json.JSONObject(json);
                org.json.JSONObject p = req.optJSONObject("properties");
                if (p == null || !p.has("id")) p = req; // 容错: 事件可能是扁平结构
                String id = p.optString("id", "");
                if (!id.isEmpty() && !permissionNotifiedKey.contains(id + ",")) {
                    permissionNotifiedKey += id + ",";
                    notifyPermissionFromEvent(p);
                }
            } else if (json.contains("message.updated")
                    || json.contains("message.part.updated")) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSseCheckMs > 2000) {
                    lastSseCheckMs = now;
                    // 立刻跑一次检测 (完成边沿); server 刚发完事件必然活着, 查询很快
                    notifyTick();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "handle event error", e);
        }
    }

    /** 通知快看护: 10s 一轮, 只做"回复结束"和"待批准"两个提醒, 与 60s 空闲看护解耦。
     *  分开后快回复 (几秒~1 分钟) 也不会漏完成通知; status() 内部有缓存, 会话无变化时
     *  每轮只拉一次小体积 /session 列表, 不重复下载大会话消息。
     *  注意: 不做前台抑制 — 应用内触发的审批/完成也要弹 (用户明确要求), 去重靠边沿+key */
    private void notifyTick() {
        if (!server.isRunning()) {
            lastStatus = null;
            serverDownTicks = 0;
            return;
        }
        // 挂死自愈: 连续 6 轮 (约 1 分钟) 探测不到 server 响应 → 事件循环卡死,
        // 重启 server 恢复 (opencode 这版权限待批会 ServeError 卡死)
        if (!server.isHealthy()) {
            if (++serverDownTicks >= 6) {
                Log.w(TAG, "server unresponsive " + serverDownTicks + " ticks, restarting");
                serverDownTicks = 0;
                server.stop();
                if (!server.isRunning() && !server.isStarting()) {
                    server.start((ok, msg) -> Log.i(TAG, "server restarted ok=" + ok), null);
                }
            }
            return;
        }
        serverDownTicks = 0;
        ServerManager.Status st = server.status();

        // 待批准提醒: agent 被权限请求卡住 (整个任务停摆), 比完成提醒更该被看见。
        // 以 GET /permission 为准: v1.18 里待批准的工具 part 状态是 running (不是 pending),
        // 只有 /permission 才返回真正的待批准请求 (含 id, 通知按钮直接回复用)。
        // 同一批请求只提醒一次; 请求被批准/拒绝后 (列表变空/变化) 自动撤掉常驻提醒
        org.json.JSONArray perms = server.listPendingPermissions();
        if (perms != null && perms.length() > 0) {
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < perms.length(); i++) {
                ids.append(perms.optJSONObject(i).optString("id", "")).append(',');
            }
            String key = ids.toString();
            if (!key.equals(permissionNotifiedKey)) {
                permissionNotifiedKey = key;
                notifyPermissionNeeded(perms, st);
            }
        } else if (perms != null && st.sessionUpdated > 0) {
            // 只在确认无待批准请求时才撤提醒; perms==null 是拉取失败(server 忙/挂),
            // 不能撤 — 否则通知会闪一下就被清掉
            clearPermissionReminder();
        }

        // 回复结束边沿: 上一轮还有未完成消息, 这一轮没有了 → 用户不在看就发通知。
        // 用 pending 而非 replying: 静默长任务 (大下载/长测试超 20 分钟无输出) 的
        // replying 会因新鲜度窗口过期变 false, 用它做边沿会漏发完成通知
        if (lastStatus != null && lastStatus.pending && !st.pending && st.sessionUpdated > 0) {
            notifyReplyFinished(st);
        }
        lastStatus = st;
        if (st.sessionTitle != null && !st.sessionTitle.isEmpty()) {
            lastSessionTitle = st.sessionTitle;
        }
    }

    private void watchdogLoop() {
        while (watchdogRunning) {
            try {
                Thread.sleep(WATCHDOG_PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!watchdogRunning) break;
            try {
                watchdogTick();
            } catch (Exception e) {
                Log.w(TAG, "watchdogTick error", e);
            }
        }
    }

    /** 空闲检测主逻辑 (后台线程) */
    private void watchdogTick() {
        if (!server.isRunning()) {
            return;
        }
        // 每轮刷新常驻通知: 密码/局域网开关/Wi-Fi IP 变更后自动同步,
        // 也保证 Web UI 设置面板里改的密码不会在通知栏残留旧值
        refreshNotification();
        int pid = server.pid();
        if (pid != lastPid) {
            lastPid = pid;
            lastTicks = -1;
        }
        long ticks = server.totalCpuTicks();
        long delta = lastTicks < 0 ? 0 : Math.max(0, ticks - lastTicks);
        lastTicks = ticks;

        boolean interactive = pm.isInteractive();
        boolean clientActive = server.clientActiveWithinMs(CLIENT_ACTIVE_MS);
        boolean busy = delta >= BUSY_TICKS;

        if (interactive && MainActivity.foreground) {
            // 用户正在看: 不需要空闲判断。
            // 跳过 status() 的全量消息拉取 (大会话每次 ~1MB JSON), 省流量省电。
            // 通知边沿检测在 10s 快看护 (notifyTick) 里做, 这里不管
            idleMinutes = 0;
            busyMinutes = 0;
            releaseWakeLock();
            return;
        }

        ServerManager.Status st = server.status();

        if (interactive) {
            // 亮屏 (不在本 App 前台): 屏幕本身保证 CPU 活跃, 不需要唤醒锁, 也不累计空闲
            idleMinutes = 0;
            busyMinutes = 0;
            releaseWakeLock();
        } else if (busy || clientActive) {
            // 息屏但有任务在跑 / 用户近期在用
            idleMinutes = 0;
            busyMinutes = busy ? busyMinutes + 1 : 0;
            acquireWakeLock();
        } else {
            // 息屏且 CPU 不忙: 只要还有未完成消息就继续保活。
            // 不能只看 replying: SSE 转发/模型等待阶段本地 CPU 极低测不到,
            // 且静默长任务的 replying 会过期; 放锁后 CPU 休眠 → 网络断 → 任务冻死。
            // 真孤儿 (server 重启留下的永久 pending) 由下面的 stalledMinutes 自愈收尾,
            // 不会像旧版那样永远占着唤醒锁。
            if (st.pending) {
                if (st.replying || busy) {
                    stalledMinutes = 0;
                } else {
                    stalledMinutes++;
                    // 连续 15 分钟既无进展也无 CPU: 基本可断定是断流孤儿,
                    // 调 abort 把消息落定 (之后正常进入空闲流程), 不杀整个 server
                    if (stalledMinutes >= ORPHAN_ABORT_MINUTES && !st.sessionId.isEmpty()) {
                        Log.i(TAG, "orphan reply (no progress " + stalledMinutes
                                + " min), aborting session " + st.sessionId);
                        server.abortSession(st.sessionId);
                        stalledMinutes = 0;
                    }
                }
                idleMinutes = 0;
                acquireWakeLock();
            } else {
                stalledMinutes = 0;
                idleMinutes++;
                busyMinutes = 0;
                if (idleMinutes >= RELEASE_MINUTES) releaseWakeLock();
            }
        }

        // 还有未完成消息时绝不参与失控强停: 息屏跑大型构建可能远超 FORCE_STOP_MINUTES。
        // 高 CPU + 无未完成消息 + 息屏 + 无客户端活动才是真的失控空转
        if (busyMinutes >= FORCE_STOP_MINUTES && !clientActive && !st.pending) {
            // 息屏 + 无客户端活动 + 非任务高 CPU → 失控进程, 强制停止防烧电
            Log.i(TAG, "runaway busy, forcing stop (busyMinutes=" + busyMinutes +
                    ", cpuDelta=" + delta + ")");
            main.post(this::stopAll);
        } else if (idleMinutes >= STOP_MINUTES) {
            Log.i(TAG, "idle, stopping server (idleMinutes=" + idleMinutes + ")");
            main.post(this::stopAll);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            // 带超时兜底: 即使逻辑异常忘记释放, 最多 10 分钟后系统也会回收;
            // 看护线程每 60s 采样, 仍需要时会重新持有
            wakeLock.acquire(10 * 60_000L);
            Log.i(TAG, "wake lock acquired (background active)");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "wake lock released (idle/screen on)");
        }
    }

    private void stopAll() {
        stopWatchdog();
        server.stop();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "server stopped");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopWatchdog();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent lan = new Intent(this, ServerService.class).setAction(ACTION_LAN_TOGGLE);
        PendingIntent lanPi = PendingIntent.getService(this, 2, lan,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean lanOn = server.isLanEnabled();
        String lanInfo = "";
        if (lanOn) {
            String url = server.lanUrl();
            lanInfo = url.isEmpty()
                    ? "\n局域网已开启 (未获取到 Wi-Fi IP)"
                    : "\n电脑/平板浏览器打开: " + url
                      + "\n用户 " + ServerManager.lanUsername() + "  密码 " + server.lanPassword();
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("OpenCode 正在运行")
                .setContentText("后台运行中 · " + (lanOn ? "局域网可访问" : "仅本机"))
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setStyle(new Notification.BigTextStyle().bigText(
                        "内置 Linux 环境后台运行中 · 空闲自动停止" + lanInfo))
                .addAction(0, lanOn ? "关闭局域网" : "开启局域网", lanPi)
                .addAction(0, "停止", stopPi);
        return b.build();
    }

    /** 局域网开关切换后刷新常驻通知 (只在内容变化时重发, 见 refreshNotification) */
    private void refreshNotification() {
        try {
            // 只在实际内容变化时才重发: 看护线程每 60s 调一次, 若每次都 nm.notify()
            // 重发, MIUI 会在用户把"OpenCode 服务"渠道调成高优先级+声音后, 每次重发都
            // 重新响铃 (实测一直有声音)。内容没变就跳过, 彻底消除周期性响铃
            String key = notifContentKey();
            if (key.equals(lastNotifKey)) return;
            lastNotifKey = key;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "refresh notification failed", e);
        }
    }

    /** 常驻通知内容指纹: 内容没变就不重发 (避免 MIUI 重发响铃) */
    private String notifContentKey() {
        boolean lanOn = server.isLanEnabled();
        String ip = lanOn ? server.lanUrl() : "";
        String pw = lanOn ? server.lanPassword() : "";
        return lanOn + "|" + ip + "|" + pw;
    }

    private String lastNotifKey = "";

    /** 回复结束提醒 (息屏/App 在后台时才发), 带会话标题和结果摘要, 点按打开 App 查看 */
    private void notifyReplyFinished(ServerManager.Status st) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "no notification permission, skip finish notice");
                return;
            }
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String title = st.sessionTitle.isEmpty() ? "OpenCode"
                    : st.sessionTitle;
            String text = st.lastText.isEmpty() ? "点按打开查看结果" : st.lastText;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, TASK_CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle((st.error ? "任务失败 · " : "回复完成 · ") + title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    // MIUI 部分版本要求通知带 ticker 才显示顶部横幅 (微信式滚动)
                    .setTicker(text)
                    .setPriority(Notification.PRIORITY_HIGH);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID_TASK, b.build());
            Log.i(TAG, "finish notice posted (error=" + st.error + ", session=" + st.sessionTitle + ")");
        } catch (Exception e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    /** 通知栏"批准/拒绝"按钮点击: 直接回复权限请求, 不用进 App */
    private void handlePermissionReply(Intent intent) {
        final String id = intent.getStringExtra("perm_id");
        final String reply = intent.getStringExtra("perm_reply");
        if (id == null || reply == null) return;
        final String label = "reject".equals(reply) ? "已拒绝" : "已批准";
        new Thread(() -> {
            boolean ok = server.replyPermission(id, reply);
            android.widget.Toast.makeText(this,
                    ok ? label + "该操作" : "操作失败 (可能已处理)",
                    android.widget.Toast.LENGTH_SHORT).show();
            Log.i(TAG, "perm reply via notif: " + id + " " + reply + " ok=" + ok);
        }, "opencode-perm-reply").start();
    }

    /** 待批准提醒 (AI 等用户批准工具调用, 整个任务停摆): 高优先级顶部横幅 (微信/短信式) + 声音震动。
     *  带"批准/拒绝"按钮, 直接从通知栏回复权限请求, 无需进 App。
     *  @param perms GET /permission 的待批准请求列表 (非空) */
    private void notifyPermissionNeeded(org.json.JSONArray perms, ServerManager.Status st) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "no notification permission, skip permission notice");
                return;
            }
            // 取第一条待批准请求: id 供按钮直接回复, permission/metadata/patterns 做描述
            org.json.JSONObject req = perms.optJSONObject(0);
            String reqId = req != null ? req.optString("id", "") : "";
            String permType = req != null ? req.optString("permission", "") : "";
            String detail = "";
            if (req != null) {
                org.json.JSONObject meta = req.optJSONObject("metadata");
                detail = meta != null ? meta.optString("command", "") : "";
                if (detail.isEmpty()) {
                    org.json.JSONArray pats = req.optJSONArray("patterns");
                    if (pats != null && pats.length() > 0) detail = pats.optString(0);
                }
            }
            String title = st.sessionTitle.isEmpty() ? "OpenCode" : st.sessionTitle;
            String text = detail.isEmpty()
                    ? "AI 请求" + (permType.isEmpty() ? "批准操作" : " " + permType) : "AI 请求: " + detail;
            postPermissionNotification(reqId, title, text);
            Log.i(TAG, "permission notice posted (session=" + st.sessionTitle
                    + ", detail=" + detail + ", req=" + reqId + ")");
        } catch (Exception e) {
            Log.w(TAG, "permission notify failed", e);
        }
    }

    /** 发"需要批准"通知 (带常驻批准/拒绝按钮的自定义布局), 轮询和 SSE 两条路共用 */
    private void postPermissionNotification(String reqId, String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, PERMISSION_CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("需要批准 · " + title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTicker(text)
                .setPriority(Notification.PRIORITY_HIGH)
                .setLights(0xFFFF0000, 500, 2000);
        if (!reqId.isEmpty()) {
            // 原生按钮: 系统默认样式, 展开通知可见
            b.addAction(0, "批准", permReplyPi(reqId, "once", 5))
             .addAction(0, "拒绝", permReplyPi(reqId, "reject", 6));
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID_PERMISSION, b.build());
    }

    /** 通知按钮的 PendingIntent: 转发到本服务 ACTION_PERM_REPLY 直接回复权限请求 */
    private PendingIntent permReplyPi(String requestId, String reply, int reqCode) {
        Intent i = new Intent(this, ServerService.class)
                .setAction(ACTION_PERM_REPLY)
                .putExtra("perm_id", requestId)
                .putExtra("perm_reply", reply);
        return PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** SSE permission.asked 事件直达通知: 负载自带全部字段, 零 HTTP —
     *  server 即将挂死也能发出去 (轮询模式赶不上的根本原因就是挂死快过轮询) */
    private void notifyPermissionFromEvent(org.json.JSONObject req) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String reqId = req.optString("id", "");
            String permType = req.optString("permission", "");
            String detail = "";
            org.json.JSONObject meta = req.optJSONObject("metadata");
            if (meta != null) detail = meta.optString("command", "");
            if (detail.isEmpty()) {
                org.json.JSONArray pats = req.optJSONArray("patterns");
                if (pats != null && pats.length() > 0) detail = pats.optString(0);
            }
            String title = lastSessionTitle == null || lastSessionTitle.isEmpty()
                    ? "OpenCode" : lastSessionTitle;
            String text = detail.isEmpty()
                    ? "AI 请求" + (permType.isEmpty() ? "批准操作" : " " + permType) : "AI 请求: " + detail;
            postPermissionNotification(reqId, title, text);
            Log.i(TAG, "permission notice via sse (detail=" + detail + ", req=" + reqId + ")");
        } catch (Exception e) {
            Log.w(TAG, "sse permission notify failed", e);
        }
    }

    /** 待批准请求已处理/用户回到 App: 撤掉提醒 (未点开过的还会留在通知栏, 正好当常驻提示) */
    private void clearPermissionReminder() {
        if (permissionNotifiedKey.isEmpty()) return;
        permissionNotifiedKey = "";
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(NOTIF_ID_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OpenCode 服务",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
        // 清理换 ID 前遗留的老渠道 (opencode_task, DEFAULT 级): 不删的话设置里会出现
        // 两个同名"任务提醒", 且老渠道的横幅/声音行为无法升级, 留着只会误导
        nm.deleteNotificationChannel("opencode_task");
        // 完成提醒: HIGH = 亮屏时顶部横幅 (微信式) + 声音震动, 不再只在状态栏留一条
        makeAlertChannel(nm, TASK_CHANNEL_ID, "任务提醒",
                "AI 回复完成/失败时提醒", new long[]{0, 300, 200, 300});
        // 待批准提醒: 高优先级渠道, 亮屏弹顶部横幅 (微信/短信式) + 声音震动
        makeAlertChannel(nm, PERMISSION_CHANNEL_ID, "需要批准",
                "AI 等待你批准工具调用时提醒 (顶部横幅+声音震动)",
                new long[]{0, 500, 300, 500, 300, 500});
    }

    /** 新建高优先级提醒渠道 (带声音震动)。注意必须用从未用过的 ID:
     *  同名渠道删除重建在进程内不生效 (MIUI 实测 mOriginalImp 不变), 无法升级重要性 */
    private void makeAlertChannel(NotificationManager nm, String id, String name,
            String desc, long[] vibrate) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(desc);
        ch.enableVibration(true);
        ch.setVibrationPattern(vibrate);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }
}