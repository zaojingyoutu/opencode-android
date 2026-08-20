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
 *     视为失控进程, 强制停止 (防止后台空转烧电)。
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";
    public static final String ACTION_START = "com.opencode.android.action.START";
    public static final String ACTION_STOP = "com.opencode.android.action.STOP";
    private static final String CHANNEL_ID = "opencode_server";
    private static final int NOTIF_ID = 1001;

    // ---- 空闲看护参数 ----
    private static final long WATCHDOG_PERIOD_MS = 60_000;        // 采样周期
    private static final long CLIENT_ACTIVE_MS = 10 * 60_000L;    // 客户端活动窗口
    private static final long SELF_HEAL_WINDOW_MS = 30 * 60_000L; // 进程被杀后自愈窗口
    private static final int BUSY_TICKS = 500;   // 60s 内 CPU ≥5s(≈8% 平均) 视为忙碌
    private static final int RELEASE_MINUTES = 3;   // 连续空闲 3 分钟 → 释放唤醒锁
    private static final int STOP_MINUTES = 30;     // 连续空闲 30 分钟 → 自动停止
    private static final int FORCE_STOP_MINUTES = 90; // 息屏无客户端仍持续高 CPU → 强制停止

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
        if (!server.isRunning()) return;
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

        if (interactive || busy || clientActive) {
            // 亮屏 / 有任务在跑 / 用户近期在用
            if (interactive) {
                idleMinutes = 0;
                busyMinutes = 0;
                releaseWakeLock();
            } else {
                idleMinutes = 0;
                busyMinutes = busy ? busyMinutes + 1 : 0;
                acquireWakeLock();
            }
        } else {
            // 疑似空闲: 再确认 AI 没有正在回复 (SSE 转发回复本地 CPU 很低, CPU 采样测不到)
            boolean replying = server.isReplying();
            if (replying) {
                idleMinutes = 0;
                busyMinutes = 0;
                acquireWakeLock();
            } else {
                idleMinutes++;
                busyMinutes = 0;
                if (idleMinutes >= RELEASE_MINUTES) releaseWakeLock();
            }
        }

        if (busyMinutes >= FORCE_STOP_MINUTES && !clientActive) {
            // 息屏 + 无客户端活动 + 持续高 CPU → 失控进程, 强制停止防烧电
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
            wakeLock.acquire();
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

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("OpenCode 正在运行")
                .setContentText("内置 Linux 环境后台运行中 · 空闲自动停止")
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, "停止", stopPi);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OpenCode 服务",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
    }
}