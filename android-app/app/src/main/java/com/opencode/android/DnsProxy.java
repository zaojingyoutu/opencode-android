package com.opencode.android;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS 代理 (127.0.0.1:53)。
 *
 * musl 编译的 Bun/opencode 在 Android 上读不到 /etc/resolv.conf,
 * 按 musl 的 fallback 会向 127.0.0.1:53 发 DNS 查询, 而该端口默认没有服务,
 * 导致所有域名解析失败 ("Unable to connect" / TimeoutError)。
 *
 * 本代理监听 127.0.0.1:53, 收到查询后用 Java InetAddress (走系统 netd DNS) 解析,
 * 构造 DNS 响应返回, 从而让 Bun 正常解析域名。
 */
public class DnsProxy {

    private static final String TAG = "OpenCodeDnsProxy";
    private static final int PORT = 53;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;

    /** 启动代理 (非阻塞) */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                socket = new DatagramSocket(PORT, InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "DNS proxy listening on 127.0.0.1:53");
                byte[] buf = new byte[512];
                while (running.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    handle(pkt);
                }
            } catch (Exception e) {
                Log.e(TAG, "DNS proxy stopped: " + e.getMessage());
            } finally {
                running.set(false);
            }
        }, "opencode-dns-proxy").start();
    }

    public void stop() {
        running.set(false);
        if (socket != null) socket.close();
    }

    private void handle(DatagramPacket pkt) {
        try {
            byte[] req = new byte[pkt.getLength()];
            System.arraycopy(pkt.getData(), pkt.getOffset(), req, 0, pkt.getLength());
            byte[] resp = resolve(req);
            if (resp != null) {
                socket.send(new DatagramPacket(resp, resp.length, pkt.getAddress(), pkt.getPort()));
            }
        } catch (Exception ignored) {
        }
    }

    /** 解析 DNS 查询并构造响应; 无法解析时返回 SOA-less NXDOMAIN 简单响应 */
    private byte[] resolve(byte[] req) throws Exception {
        if (req.length < 12) return null;
        ByteBuffer in = ByteBuffer.wrap(req);
        int id = in.getShort() & 0xFFFF;
        int flags = in.getShort() & 0xFFFF;
        int qdcount = in.getShort() & 0xFFFF;
        in.getShort(); // ancount
        in.getShort(); // nscount
        in.getShort(); // arcount
        if (qdcount != 1) return null;

        String name = readQName(in);
        if (name == null) return null;
        int qtype = in.getShort() & 0xFFFF;
        int qclass = in.getShort() & 0xFFFF;

        if (qclass != 1 || (qtype != 1 && qtype != 28 && qtype != 255)) {
            // 不支持的类型: 返回空应答 (noerror, 0 answers)
            return buildResponse(id, 0x8180, req, new byte[0][]);
        }

        InetAddress[] addrs = InetAddress.getAllByName(name);
        int count = 0;
        for (InetAddress a : addrs) {
            boolean wantV4 = qtype == 1 || qtype == 255;
            boolean wantV6 = qtype == 28 || qtype == 255;
            if ((a instanceof Inet4Address && wantV4) || (a instanceof Inet6Address && wantV6)) count++;
        }
        byte[][] answers = new byte[count][];
        int idx = 0;
        for (InetAddress a : addrs) {
            boolean wantV4 = qtype == 1 || qtype == 255;
            boolean wantV6 = qtype == 28 || qtype == 255;
            if ((a instanceof Inet4Address && wantV4) || (a instanceof Inet6Address && wantV6)) {
                answers[idx++] = a.getAddress();
            }
        }
        return buildResponse(id, 0x8180, req, answers);
    }

    /** 读取 DNS 查询名, 返回如 "example.com" (相对指针在 query 中不应出现) */
    private String readQName(ByteBuffer in) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int len = in.get() & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0) return null; // 压缩指针在查询里不合法
            if (in.remaining() < len) return null;
            byte[] label = new byte[len];
            in.get(label);
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(label, java.nio.charset.StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    /** 构造响应: header + 原 question + answer 记录 */
    private byte[] buildResponse(int id, int flags, byte[] questionStart, byte[][] answers) {
        int qLen = findQuestionLength(questionStart);
        int size = 12 + qLen;
        for (byte[] a : answers) size += 2 + 2 + 2 + 4 + 2 + a.length; // name ptr + type + class + ttl + rdlen + rdata
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putShort((short) id);
        out.putShort((short) flags);
        out.putShort((short) 1); // qdcount
        out.putShort((short) answers.length); // ancount
        out.putShort((short) 0);
        out.putShort((short) 0);
        out.put(questionStart, 12, qLen);
        for (byte[] a : answers) {
            out.putShort((short) 0xC00C); // 指向 question 的 name
            out.putShort((short) (a.length == 4 ? 1 : 28)); // A / AAAA
            out.putShort((short) 1); // class IN
            out.putInt(60); // TTL
            out.putShort((short) a.length);
            out.put(a);
        }
        return out.array();
    }

    /** question 区长度 (从偏移 12 到 question 结束) */
    private int findQuestionLength(byte[] req) {
        int off = 12;
        while (true) {
            int len = req[off] & 0xFF;
            if (len == 0) return off + 5 - 12; // name 结束 + qtype(2) + qclass(2)
            off += len + 1;
        }
    }
}
