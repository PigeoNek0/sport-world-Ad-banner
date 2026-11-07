package com.zszl.netkit;

import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简双栈 DNS 代理：从 tun 读 UDP/53 报文，命中清单就本地伪造应答（0.0.0.0 / ::），
 * 否则转发给上游 DNS 并把应答写回 tun。
 *
 * 设计要点：
 *  - 命中的查询在主循环里同步应答（微秒级，不阻塞）；
 *  - 未命中的查询丢进线程池异步转发，避免单个上游超时把整条 tun 读循环（乃至整机 DNS）堵住；
 *  - 日志按级别分流：命中打 INFO，放行打 DEBUG，避免每包一条 INFO 的开销；
 *  - IPv6 走扩展头链（0/43/44/51/60）找 UDP，不再假设 next header 直接是 17。
 *
 * 只处理 UDP；TCP DNS（少见）会被丢弃，客户端通常会退回 UDP。
 */
public final class DnsProxy implements Runnable {

    private static final String TAG = "NetKitDns";

    private static final int MAX_PACKET = 4096;
    private static final int UPSTREAM_TIMEOUT_MS = 2000;

    private final FileInputStream in;
    private final FileOutputStream out;
    private final FilterList filter;
    private final List<String> upstreams;
    private final VpnGuard guard;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = true;

    public interface VpnGuard {
        /** 让上游 socket 绕过 VPN 自身，避免回环。 */
        void protectSocket(java.net.DatagramSocket socket);
    }

    public DnsProxy(FileInputStream in, FileOutputStream out, FilterList filter,
                    List<String> upstreams, VpnGuard guard) {
        this.in = in;
        this.out = out;
        this.filter = filter;
        this.upstreams = upstreams;
        this.guard = guard;
    }

    public void stop() {
        running = false;
        try {
            pool.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void run() {
        byte[] buf = new byte[MAX_PACKET];
        while (running) {
            int len;
            try {
                len = in.read(buf);
            } catch (Exception e) {
                if (running) Log.w(TAG, "tun read end: " + e);
                break;
            }
            if (len <= 0) continue;
            byte[] pkt = Arrays.copyOf(buf, len);
            try {
                dispatch(pkt);
            } catch (Exception e) {
                Log.w(TAG, "handle error: " + e);
            }
        }
        Log.i(TAG, "proxy loop exited");
    }

    private void dispatch(byte[] p) throws Exception {
        Parsed q = parse(p);
        if (q == null) return;                     // 非 UDP/53，直接忽略（ICMPv6、MLD 等）
        if (q.qname == null) return;

        if (filter.isBlocked(q.qname)) {
            byte[] resp = synthBlocked(p, q);
            Stats.onBlocked(q.qname);
            Log.i(TAG, "BLOCK " + q.qname + " t=" + q.qtype);
            writeBack(p, q, resp);
            return;
        }

        Log.d(TAG, "pass " + q.qname + " t=" + q.qtype);
        if (!pool.isShutdown()) {
            final byte[] query = Arrays.copyOfRange(p, q.payloadOff, q.payloadOff + q.payloadLen);
            final Parsed fq = q;
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        byte[] resp = forward(query);
                        if (resp == null) resp = synthFailureFrom(query);
                        try {
                            writeBack(null, fq, resp);
                        } catch (Exception e) {
                            Log.w(TAG, "write back failed: " + e);
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "pool rejected: " + e);
            }
        }
    }

    // ---------- 报文解析 ----------

    /** 解析结果：源/目的地址偏移、端口、UDP 负载位置。 */
    private static final class Parsed {
        boolean v6;
        int srcOff, dstOff, addrLen;
        int srcPort, dstPort;
        int payloadOff, payloadLen;
        byte[] srcAddr, dstAddr;   // 异步转发路径下用缓存的地址回封
        String qname;
        int qtype;
    }

    private static Parsed parse(byte[] p) {
        int len = p.length;
        int version = (p[0] & 0xf0) >> 4;
        Parsed r = new Parsed();
        r.v6 = version == 6;
        int udpOff;
        if (r.v6) {
            if (len < 48) return null;
            udpOff = ipv6UdpOffset(p, len);
            if (udpOff < 0) return null;
            r.srcOff = 8;
            r.dstOff = 24;
            r.addrLen = 16;
        } else {
            if (version != 4 || len < 28) return null;
            if ((p[9] & 0xff) != 17) return null;
            udpOff = (p[0] & 0x0f) * 4;
            r.srcOff = 12;
            r.dstOff = 16;
            r.addrLen = 4;
        }
        if (udpOff < 0 || len < udpOff + 8) return null;

        r.srcAddr = Arrays.copyOfRange(p, r.srcOff, r.srcOff + r.addrLen);
        r.dstAddr = Arrays.copyOfRange(p, r.dstOff, r.dstOff + r.addrLen);
        r.srcPort = u16(p, udpOff);
        r.dstPort = u16(p, udpOff + 2);
        int udpLen = u16(p, udpOff + 4);
        if (r.dstPort != 53 || udpLen < 8) return null;

        r.payloadOff = udpOff + 8;
        r.payloadLen = Math.min(udpLen - 8, len - r.payloadOff);
        if (r.payloadLen < 12) return null;

        Log.d(TAG, "query " + (r.v6 ? "v6" : "v4") + " " + r.srcPort + "->53 len=" + r.payloadLen);
        r.qname = readQName(p, r.payloadOff, r.payloadLen);
        if (r.qname == null) return null;
        r.qtype = qtype(p, r.payloadOff, r.payloadLen);
        return r;
    }

    /** 走 IPv6 扩展头链（0 hop-by-hop / 43 routing / 44 fragment / 51 AH / 60 dest-opts）定位 UDP。 */
    private static int ipv6UdpOffset(byte[] p, int len) {
        int nh = p[6] & 0xff;
        int off = 40;
        for (int guard = 0; guard < 8; guard++) {
            if (nh == 17) return off;                       // UDP
            if (nh == 0 || nh == 43 || nh == 60) {          // 8n+8 长度
                if (off + 2 > len) return -1;
                int extLen = 8 + (p[off + 1] & 0xff) * 8;
                nh = p[off] & 0xff;
                off += extLen;
            } else if (nh == 51) {                          // AH: (len+2)*4
                if (off + 2 > len) return -1;
                int ahLen = ((p[off + 1] & 0xff) + 2) * 4;
                nh = p[off] & 0xff;
                off += ahLen;
            } else if (nh == 44) {                          // fragment: 8 字节，后续头在 +8
                if (off + 8 > len) return -1;
                nh = p[off] & 0xff;
                off += 8;
            } else {
                return -1;                                  // ICMPv6(58)、TCP(6) 等
            }
            if (off < 0 || off > len) return -1;
        }
        return -1;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xff);
        b[off + 1] = (byte) (v & 0xff);
    }

    /** 解析查询名；成功返回小写域名，失败 null。 */
    private static String readQName(byte[] b, int base, int len) {
        try {
            StringBuilder sb = new StringBuilder();
            int i = base + 12;
            int end = base + len;
            while (i < end) {
                int l = b[i] & 0xff;
                if (l == 0) break;
                if ((l & 0xc0) != 0) return null;   // 查询里不出现压缩指针
                i++;
                if (i + l > end) return null;
                if (sb.length() > 0) sb.append('.');
                for (int k = 0; k < l; k++) sb.append((char) (b[i + k] & 0xff));
                i += l;
            }
            String s = sb.toString().toLowerCase();
            return s.length() == 0 ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int qtype(byte[] b, int base, int len) {
        int i = base + 12;
        int end = base + len;
        while (i < end) {
            int l = b[i] & 0xff;
            if (l == 0) { i++; break; }
            if ((l & 0xc0) != 0) { i += 2; break; }
            i += 1 + l;
        }
        if (i + 2 > end) return 1;
        return u16(b, i);
    }

    private static int questionEnd(byte[] b, int base, int len) {
        int i = base + 12;
        int end = base + len;
        while (i < end) {
            int l = b[i] & 0xff;
            if (l == 0) { i++; break; }
            if ((l & 0xc0) != 0) { i += 2; break; }
            i += 1 + l;
        }
        return Math.min(i + 4, end);
    }

    // ---------- 应答构造 ----------

    private static byte[] header(byte[] q, int base, int rcode) {
        byte[] h = new byte[12];
        h[0] = q[base];
        h[1] = q[base + 1];
        h[2] = (byte) 0x81;          // QR=1, RD=1
        h[3] = (byte) (0x80 | rcode); // RA=1
        put16(h, 4, 1);               // qdcount
        return h;
    }

    /** 命中清单：A -> 0.0.0.0，AAAA -> ::，其它类型给空应答。 */
    private static byte[] synthBlocked(byte[] q, Parsed pq) {
        int base = pq.payloadOff, len = pq.payloadLen;
        int qe = questionEnd(q, base, len);
        int qLen = qe - base;
        boolean hasAnswer = pq.qtype == 1 || pq.qtype == 28;
        int rdLen = pq.qtype == 1 ? 4 : 16;
        byte[] r = new byte[12 + (qLen - 12) + (hasAnswer ? 12 + rdLen : 0)];
        byte[] h = header(q, base, 0);
        if (hasAnswer) put16(h, 6, 1);   // ancount
        System.arraycopy(h, 0, r, 0, 12);
        System.arraycopy(q, base + 12, r, 12, qLen - 12);
        if (hasAnswer) {
            int o = 12 + (qLen - 12);
            r[o] = (byte) 0xc0;
            r[o + 1] = 0x0c;              // 指向问题名
            put16(r, o + 2, pq.qtype);
            put16(r, o + 4, 1);           // class IN
            r[o + 8] = 60;                // ttl
            put16(r, o + 10, rdLen);      // 地址全零 = 0.0.0.0 / ::
        }
        return r;
    }

    private static byte[] synthFailureFrom(byte[] query) {
        int qe = questionEnd(query, 0, query.length);
        byte[] r = new byte[qe];
        System.arraycopy(header(query, 0, 2), 0, r, 0, 12);   // SERVFAIL
        System.arraycopy(query, 12, r, 12, qe - 12);
        return r;
    }

    private byte[] forward(byte[] query) {
        for (String up : upstreams) {
            DatagramSocket s = null;
            try {
                InetAddress addr = InetAddress.getByName(up);
                s = new DatagramSocket();
                if (guard != null) guard.protectSocket(s);
                s.setSoTimeout(UPSTREAM_TIMEOUT_MS);
                s.send(new DatagramPacket(query, query.length, addr, 53));
                byte[] buf = new byte[4096];   // EDNS0 下 UDP 应答可达 4096，1500 会截断
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                s.receive(rp);
                return Arrays.copyOf(buf, rp.getLength());
            } catch (Exception e) {
                Log.d(TAG, "upstream " + up + " failed: " + e);
            } finally {
                if (s != null) s.close();
            }
        }
        return null;
    }

    private void writeBack(byte[] orig, Parsed pq, byte[] payload) throws Exception {
        byte[] pkt = pq.v6
                ? buildIpv6(orig, pq, payload)
                : buildIpv4(orig, pq, payload);
        synchronized (out) {
            out.write(pkt);
        }
        Log.d(TAG, "wrote " + pkt.length + " bytes");
    }

    // ---------- 回封 IP/UDP ----------

    /**
     * orig 为 null 时（异步路径）用 Parsed 里缓存的地址重建；因此 Parsed 也缓存了地址字节。
     */
    private static byte[] buildIpv4(byte[] orig, Parsed pq, byte[] payload) {
        byte[] src = pq.srcAddr, dst = pq.dstAddr;
        if (orig != null) {
            src = Arrays.copyOfRange(orig, pq.srcOff, pq.srcOff + 4);
            dst = Arrays.copyOfRange(orig, pq.dstOff, pq.dstOff + 4);
        }
        int total = 20 + 8 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[6] = 0x40;
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(dst, 0, p, 12, 4);   // 源 = 虚拟 DNS
        System.arraycopy(src, 0, p, 16, 4);   // 目的 = 原客户端
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += u16(p, i);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        put16(p, 10, ~sum & 0xffff);
        put16(p, 20, 53);
        put16(p, 22, pq.srcPort);
        put16(p, 24, 8 + payload.length);
        put16(p, 26, 0);
        System.arraycopy(payload, 0, p, 28, payload.length);
        return p;
    }

    private static byte[] buildIpv6(byte[] orig, Parsed pq, byte[] payload) {
        byte[] src = pq.srcAddr, dst = pq.dstAddr;
        if (orig != null) {
            src = Arrays.copyOfRange(orig, pq.srcOff, pq.srcOff + 16);
            dst = Arrays.copyOfRange(orig, pq.dstOff, pq.dstOff + 16);
        }
        int total = 40 + 8 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x60;
        put16(p, 4, 8 + payload.length);
        p[6] = 17;
        p[7] = 64;
        System.arraycopy(dst, 0, p, 8, 16);
        System.arraycopy(src, 0, p, 24, 16);
        put16(p, 40, 53);
        put16(p, 42, pq.srcPort);
        put16(p, 44, 8 + payload.length);
        System.arraycopy(payload, 0, p, 48, payload.length);
        int sum = 0;
        for (int i = 8; i < 40; i += 2) sum += u16(p, i);
        sum += (8 + payload.length);
        sum += 17;
        for (int i = 40; i < total; i += 2) {
            sum += (i + 1 < total) ? u16(p, i) : ((p[i] & 0xff) << 8);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        int ck = (~sum) & 0xffff;
        if (ck == 0) ck = 0xffff;
        put16(p, 46, ck);
        return p;
    }

    public static List<String> defaultUpstreams() {
        List<String> l = new ArrayList<String>();
        l.add("223.5.5.5");
        l.add("119.29.29.29");
        return l;
    }
}
