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
    private static final String CHANNEL_ID = "opencode_server";
    private static final int NOTIF_ID = 1001;
    /** 任务事件通知渠道 (回复完成/失败提醒, 带声音, 与常驻服务的静音渠道分开便于用户分别管理) */
    private static final String TASK_CHANNEL_ID = "opencode_task";
    private static final int NOTIF_ID_TASK = 1002;

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
    /** 上一轮看护采样到的状态, 用于检测"回复结束"边沿 (发完成通知) */
    private ServerManager.Status lastStatus;

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
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
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
        lastStatus = null;
        watchdog = new Thread(this::watchdogLoop, "opencode-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        if (watchdog != null) watchdog.interrupt();
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
            lastStatus = null;
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
            // 用户正在看: 不需要完成通知, 也不需要空闲判断。
            // 跳过 status() 的全量消息拉取 (大会话每次 ~1MB JSON), 省流量省电
            lastStatus = null;
            idleMinutes = 0;
            busyMinutes = 0;
            releaseWakeLock();
            return;
        }

        ServerManager.Status st = server.status();

        // 回复结束边沿: 上一轮还有未完成消息, 这一轮没有了 → 用户不在看就发通知。
        // 用 pending 而非 replying: 静默长任务 (大下载/长测试超 20 分钟无输出) 的
        // replying 会因新鲜度窗口过期变 false, 用它做边沿会漏发完成通知
        if (lastStatus != null && lastStatus.pending && !st.pending && st.sessionUpdated > 0) {
            notifyReplyFinished(st);
        }
        lastStatus = st;

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

    /** 局域网开关切换后刷新常驻通知 */
    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "refresh notification failed", e);
        }
    }

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
                    .setAutoCancel(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID_TASK, b.build());
            Log.i(TAG, "finish notice posted (error=" + st.error + ", session=" + st.sessionTitle + ")");
        } catch (Exception e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OpenCode 服务",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
        NotificationChannel task = new NotificationChannel(TASK_CHANNEL_ID, "任务提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        task.setDescription("AI 回复完成/失败时提醒");
        nm.createNotificationChannel(task);
    }
}