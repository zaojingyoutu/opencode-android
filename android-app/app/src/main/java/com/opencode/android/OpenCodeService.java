package com.opencode.android;

import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Monitors whether the local opencode server is reachable.
 * The opencode CLI must be running in Termux, not inside this app.
 * This service does periodic health checks and updates server URL preference.
 */
public class OpenCodeService extends Service {
    private static final String TAG = "OpenCodeService";
    private Handler handler;
    private int port = 18888;
    private SharedPreferences prefs;
    private boolean running = false;

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            String url = prefs.getString("server_url", "http://localhost:18888");
            if (url != null && !url.isEmpty()) {
                int idx = url.indexOf(':');
                if (idx > 0) {
                    try { port = Integer.parseInt(url.substring(idx + 1).split("/")[0]); }
                    catch (Exception e) {}
                }
            }
            final int finalPort = port;
            new Thread(() -> {
                try {
                    URL healthUrl = new URL("http://localhost:" + finalPort + "/health");
                    HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    // 如果 health endpoint 不存在，试根路径
                    if (code == 404 || code == 405) {
                        URL rootUrl = new URL("http://localhost:" + finalPort);
                        HttpURLConnection conn2 = (HttpURLConnection) rootUrl.openConnection();
                        conn2.setRequestMethod("HEAD");
                        conn2.setConnectTimeout(2000);
                        int code2 = conn2.getResponseCode();
                        conn2.disconnect();
                        code = code2;
                    }
                } catch (Exception e) {
                    // server not ready yet
                }
            }).start();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        String savedUrl = intent != null ? intent.getStringExtra("server_url") : null;
        if (savedUrl != null && !savedUrl.isEmpty()) {
            prefs.edit().putString("server_url", savedUrl).apply();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, new android.app.Notification.Builder(
                    getApplicationContext(), "opencode_channel")
                    .setContentTitle("OpenCode")
                    .setContentText("Monitoring local server...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(android.app.Notification.PRIORITY_LOW)
                    .build());
        }
        handler.post(healthCheck);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(healthCheck);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}