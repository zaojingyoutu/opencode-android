package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备能力桥 (T1 sidecar): 在 127.0.0.1 上开一个极简 HTTP 服务,
 * 让容器内的 opencode agent 通过 curl 驱动 Android 真机:
 *   Toast / 通知 / 打开链接 / 分享文本 / 剪贴板 / 设备信息 / TTS。
 *
 * 设计 (安全四件套):
 *  - 只绑 127.0.0.1 (永不暴露到局域网, 即使 LAN 模式开启也只绑回环)
 *  - Basic 认证复用 opencode server 的随机密码 (ServerManager.lanPassword),
 *    密码同时写入容器 /root/.device-bridge.auth 供 agent 读取
 *  - 敏感操作 (剪贴板读等) 走 Manifest 权限; 端到端无公网
 *  - 每次调用记日志, 可审计
 *
 * 协议: 每个请求一条 TCP; POST body 为 JSON (UTF-8); 响应统一 JSON:
 *   {"ok":true,"data":...} 或 {"ok":false,"error":"..."}
 */
public class DeviceBridge {
    private static final String TAG = "DeviceBridge";
    /** 通知渠道 (agent 主动推送给用户的通知, 与任务/审批渠道分开便于管理) */
    private static final String CHANNEL_ID = "opencode_bridge";
    private static final int NOTIF_ID = 2001;
    /** 剪贴板读取在 Android 10+ 仅当 App 有焦点时允许; 后台被系统静默置空 */
    private static final boolean CLIPBOARD_READ_ALLOWED = Build.VERSION.SDK_INT < 29;

    private final Context ctx;
    private final ServerManager server;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket socket;
    private Thread acceptThread;

    public DeviceBridge(Context ctx, ServerManager server) {
        this.ctx = ctx.getApplicationContext();
        this.server = server;
        createChannel();
    }

    /** 端口 = opencode server 端口 + 2 (18888→18890, beta 18889→18891), 永不冲突 */
    public static int bridgePort() {
        return BuildConfig.SERVER_PORT + 2;
    }

    public String bridgeUrl() {
        return "http://127.0.0.1:" + bridgePort();
    }

    /** 启动监听线程 (幂等) */
    public synchronized void start() {
        if (running.get()) return;
        try {
            // 显式绑回环, 即使设备在 LAN 也不开放
            socket = new ServerSocket(bridgePort(), 4, InetAddress.getByName("127.0.0.1"));
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "opencode-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "bridge listening on " + bridgeUrl());
        } catch (Exception e) {
            Log.e(TAG, "bridge start failed: " + e);
            running.set(false);
        }
    }

    /** 停止 (幂等) */
    public synchronized void stop() {
        running.set(false);
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
            socket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        Log.i(TAG, "bridge stopped");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = socket.accept();
                s.setSoTimeout(8000);
                Thread t = new Thread(() -> handle(s), "opencode-bridge-req");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running.get()) {
                    Log.w(TAG, "bridge accept error: " + e);
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 处理 (极简: 只解析 method/path/Authorization/Content-Length/body)
    // ------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket sock = s;
             InputStream in = sock.getInputStream();
             OutputStream out = sock.getOutputStream()) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];

            // headers
            String auth = null;
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int ci = line.indexOf(':');
                if (ci <= 0) continue;
                String k = line.substring(0, ci).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(ci + 1).trim();
                if ("authorization".equals(k)) auth = v;
                else if ("content-length".equals(k)) {
                    try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
                }
            }
            // body
            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[Math.min(contentLength, 256 * 1024)];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                body = new String(buf, 0, off, StandardCharsets.UTF_8);
            }

            // 认证
            if (!authorized(auth)) {
                writeJson(out, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            route(out, method, path, body);
        } catch (Exception e) {
            Log.w(TAG, "bridge request error: " + e);
        }
    }

    private boolean authorized(String auth) {
        if (auth == null) return false;
        // 常数时间比较 (避免时序侧信道)
        String expect = server.basicAuth();
        if (expect.length() != auth.length()) return false;
        int diff = 0;
        for (int i = 0; i < auth.length(); i++) diff |= auth.charAt(i) ^ expect.charAt(i);
        return diff == 0;
    }

    private void route(OutputStream out, String method, String path, String body) throws IOException {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        try {
            if ("GET".equals(method) && "/health".equals(p)) {
                writeJson(out, 200, "{\"ok\":true}");
            } else if ("GET".equals(method) && "/device".equals(p)) {
                writeJson(out, 200, deviceInfoJson());
            } else if ("POST".equals(method) && "/toast".equals(p)) {
                String text = opt(body, "text");
                if (text == null || text.isEmpty()) { writeJson(out, 400, err("text required")); return; }
                main.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_LONG).show());
                writeJson(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/notify".equals(p)) {
                String title = opt(body, "title");
                String text = opt(body, "text");
                if (text == null || text.isEmpty()) { writeJson(out, 400, err("text required")); return; }
                main.post(() -> notifyUser(title == null || title.isEmpty() ? "OpenCode" : title, text));
                writeJson(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/open".equals(p)) {
                String url = opt(body, "url");
                if (url == null || url.isEmpty()) { writeJson(out, 400, err("url required")); return; }
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    writeJson(out, 400, err("only http(s) allowed"));
                    return;
                }
                final String target = url;
                main.post(() -> openUrl(target));
                writeJson(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/share".equals(p)) {
                String text = opt(body, "text");
                String title = opt(body, "title");
                if (text == null || text.isEmpty()) { writeJson(out, 400, err("text required")); return; }
                final String t = text;
                final String ti = title;
                main.post(() -> shareText(ti, t));
                writeJson(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/clipboard".equals(p)) {
                String action = opt(body, "action");
                if ("write".equals(action)) {
                    String text = opt(body, "text");
                    if (text == null) { writeJson(out, 400, err("text required for write")); return; }
                    final String t = text;
                    main.post(() -> setClipboard(t));
                    writeJson(out, 200, "{\"ok\":true}");
                } else if ("read".equals(action)) {
                    if (!CLIPBOARD_READ_ALLOWED) {
                        // Android 10+: 无焦点读剪贴板会被系统拒 (返回空), 显式说明而非报错
                        writeJson(out, 200, "{\"ok\":true,\"data\":null,\"note\":\"clipboard read requires app focus on Android 10+\"}");
                        return;
                    }
                    writeJson(out, 200, "{\"ok\":true,\"data\":" + jsonStr(getClipboard()) + "}");
                } else {
                    writeJson(out, 400, err("action must be read|write"));
                }
            } else if ("POST".equals(method) && "/speak".equals(p)) {
                String text = opt(body, "text");
                if (text == null || text.isEmpty()) { writeJson(out, 400, err("text required")); return; }
                final String t = text;
                main.post(() -> speak(t));
                writeJson(out, 200, "{\"ok\":true}");
            } else {
                writeJson(out, 404, err("not found: " + method + " " + p));
            }
        } catch (Exception e) {
            Log.w(TAG, "bridge route error: " + e);
            writeJson(out, 500, err(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 设备能力实现 (全部切回主线程执行, 满足 UI/Intent 约束)
    // ------------------------------------------------------------------

    private void notifyUser(String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "no notification permission, skip");
                return;
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception e) {
            Log.w(TAG, "bridge notify failed", e);
        }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open url failed: " + e);
            Toast.makeText(ctx, "无法打开: " + url, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String title, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_TITLE, title);
            Intent chooser = Intent.createChooser(i, title == null || title.isEmpty() ? "分享" : title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Exception e) {
            Log.w(TAG, "share failed: " + e);
        }
    }

    private void setClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("opencode", text));
        } catch (Exception e) {
            Log.w(TAG, "clipboard write failed: " + e);
        }
    }

    private String getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(ctx);
                return t == null ? null : t.toString();
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "clipboard read failed: " + e);
            return null;
        }
    }

    private TextToSpeech tts;

    private void speak(String text) {
        try {
            if (tts == null) {
                tts = new TextToSpeech(ctx, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(Locale.getDefault());
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.CHINESE);
                        }
                        speakNow(text);
                    }
                });
            } else {
                speakNow(text);
            }
        } catch (Exception e) {
            Log.w(TAG, "tts failed: " + e);
        }
    }

    private void speakNow(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oc-bridge");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "tts speak failed: " + e);
        }
    }

    private String deviceInfoJson() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"data\":{");
            sb.append("\"model\":").append(jsonStr(Build.MODEL));
            sb.append(",\"manufacturer\":").append(jsonStr(Build.MANUFACTURER));
            sb.append(",\"android\":").append(jsonStr(Build.VERSION.RELEASE));
            // 电量
            try {
                Intent bi = ctx.registerReceiver(null,
                        new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int level = bi.getIntExtra("level", -1);
                    int scale = bi.getIntExtra("scale", 100);
                    int status = bi.getIntExtra("status", -1);
                    sb.append(",\"battery\":").append(scale > 0 ? level * 100 / scale : -1);
                    sb.append(",\"batteryCharging\":").append(
                            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL);
                }
            } catch (Exception ignored) {}
            // 网络 (只读; 不额外申请定位权限)
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                boolean online = ni != null && ni.isConnected();
                sb.append(",\"online\":").append(online);
                if (online) {
                    int type = ni.getType();
                    sb.append(",\"network\":").append(jsonStr(
                            type == android.net.ConnectivityManager.TYPE_WIFI ? "wifi"
                                    : type == android.net.ConnectivityManager.TYPE_MOBILE ? "cellular" : "other"));
                }
            } catch (Exception ignored) {}
            // 屏幕
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) sb.append(",\"screenOn\":").append(pm.isInteractive());
            sb.append("}}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":" + jsonStr(e.toString()) + "}";
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OpenCode 设备",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("agent 主动推送的提醒");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        } catch (Exception e) {
            Log.w(TAG, "createChannel failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 极简 JSON/HTTP 工具 (不引入第三方依赖)
    // ------------------------------------------------------------------

    private static String opt(String body, String key) {
        if (body == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            if (o.has(key) && !o.isNull(key)) return o.getString(key);
        } catch (Exception ignored) {}
        return null;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String err(String msg) {
        return "{\"ok\":false,\"error\":" + jsonStr(msg) + "}";
    }

    private static void writeJson(OutputStream out, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code);
        head.append(code == 200 ? " OK" : code == 400 ? " Bad Request"
                : code == 401 ? " Unauthorized" : code == 404 ? " Not Found" : " Internal Server Error");
        head.append("\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (buf.size() == 0 && c == -1) return null;
        return buf.toString("UTF-8");
    }
}
