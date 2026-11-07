package com.zszl.netkit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 本地 DNS 过滤 VPN：只接管被选中的应用（Q21/Q22）。
 *
 * 路由策略：只把「发往虚拟 DNS 的报文」导入 tun，其余流量（HTTP/HTTPS/其它 UDP）
 * 保持物理网络直连 —— 这样既能让目标 App 的 DNS 走我们，又不会把它的正常网络黑洞掉。
 * 必须同时提供 IPv4 与 IPv6 虚拟 DNS：只给 v4 时系统会对 v6 加 ::/0 unreachable，
 * App 的 AAAA 查询全部失败，表现为「请检查您的网络设置」。
 */
public class AdVpnService extends VpnService implements DnsProxy.VpnGuard {

    private static final String TAG = "NetKitVpn";
    private static final String CHANNEL = "netkit";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.zszl.netkit.action.START";
    public static final String ACTION_STOP = "com.zszl.netkit.action.STOP";
    /** 配置变更（EXTRA/大清单/上游）后原地重载，避免 stop+start 竞态把新代理线程打死。 */
    public static final String ACTION_RELOAD = "com.zszl.netkit.action.RELOAD";
    /** 服务状态变更广播：界面据此刷新，不再靠 Stats 轮询猜状态。 */
    public static final String ACTION_STATE = "com.zszl.netkit.action.STATE";

    /**
     * 关键：**接口地址必须和 DNS 服务器地址不同**。
     * 若把 10.111.222.2 同时当作 tun 的接口地址，内核会把它视为本地地址，
     * 发往它的报文被本机消费、根本不进 tun 的读队列（表现就是 tun0 RX=0、
     * App 报「请检查您的网络设置」）。所以接口用 .1、DNS 用 .2，再加 /32 路由。
     */
    private static final String V4_ADDR = "10.111.222.1";
    private static final String V4_DNS = "10.111.222.2";
    private static final String V6_ADDR = "fd00:1:fd00:1::1";
    private static final String V6_DNS = "fd00:1:fd00:1::2";

    private ParcelFileDescriptor tun;
    private DnsProxy proxy;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "stop requested, from=" + (intent == null ? "?" : intent.getStringExtra("from")));
            stopVpn(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            Log.i(TAG, "reload requested (config changed)");
            stopVpn(false);
            final AdVpnService self = this;
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    startVpn();
                }
            }, 400);
            return START_STICKY;
        }        if (tun != null) {
            Log.i(TAG, "already running");
            notifyState(true);
            return START_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        startForegroundCompat();

        Set<String> pkgs = Prefs.packages(this);
        Builder b = new Builder();
        b.setSession("netkit");
        b.setMtu(1500);
        b.addAddress(V4_ADDR, 32);
        b.addAddress(V6_ADDR, 128);
        b.addDnsServer(V4_DNS);
        b.addDnsServer(V6_DNS);
        b.addRoute(V4_DNS, 32);
        b.addRoute(V6_DNS, 128);
        b.setBlocking(true);
        if (Build.VERSION.SDK_INT >= 29) {
            b.setMetered(false);
        }

        // per-app：只接管选中的应用。注意 addAllowedApplication 要求包名已安装，
        // 未安装会抛 IllegalArgumentException —— 这里逐个容错，宁可少接管也不崩。
        // 注意：Android 15/16 上 addAllowedApplication() 对「未安装的包名」并不抛异常，
        // 只写进 allowlist。所以不能只靠异常判断，必须用 PackageManager 核对是否真的装着，
        // 否则会出现「App 已卸载但 VPN 照常起来、看起来在过滤其实什么都没接管」。
        int allowed = 0;
        for (String pkg : pkgs) {
            if (pkg == null || pkg.trim().length() == 0) continue;
            String name = pkg.trim();
            try {
                getPackageManager().getPackageInfo(name, 0);
            } catch (Exception e) {
                Log.w(TAG, "target pkg not installed, skip: " + name);
                continue;
            }
            try {
                b.addAllowedApplication(name);
                allowed++;
            } catch (Exception e) {
                Log.w(TAG, "skip allowed pkg " + name + ": " + e);
            }
        }
        if (allowed == 0) {
            // 一个 allowed 都没有时 Builder 会接管「所有 App」的 DNS —— 违背验收②
            // （其他 App 完全不受影响）。宁可不开，也不全局接管。
            // 不清 Prefs.enabled：目标 App 重新装上后，开机/手动开启仍能恢复。
            Log.e(TAG, "no installed target package in " + pkgs + ", refuse to start (would capture all apps)");
            try {
                android.widget.Toast.makeText(this, "所选应用均未安装，过滤未开启",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            stopVpn(false);
            stopSelf();
            return;
        }
        Log.i(TAG, "per-app DNS capture, allowed=" + allowed + " targets=" + pkgs);

        try {
            tun = b.establish();
        } catch (Exception e) {
            Log.e(TAG, "establish failed: " + e);
            stopVpn(false);
            stopSelf();
            return;
        }
        if (tun == null) {
            Log.e(TAG, "establish returned null (no permission?)");
            stopVpn(false);
            stopSelf();
            return;
        }

        FilterList filter = new FilterList();
        filter.load(this, Prefs.extra(this), Prefs.big(this));

        List<String> ups = new ArrayList<String>();
        String custom = Prefs.upstream(this);
        if (custom != null && custom.trim().length() > 0) {
            for (String s : custom.split(",")) {
                String t = s.trim();
                if (t.length() > 0) ups.add(t);
            }
        }
        if (ups.isEmpty()) ups = DnsProxy.defaultUpstreams();

        try {
            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            proxy = new DnsProxy(in, out, filter, ups, this);
            worker = new Thread(proxy, "netkit-dns");
            worker.start();
            Stats.setRunning(true);
            Prefs.setEnabled(this, true);
            Log.i(TAG, "vpn up, hosts=" + filter.size() + " upstreams=" + ups);
            notifyState(true);
        } catch (Exception e) {
            Log.e(TAG, "start proxy failed: " + e);
            stopVpn(false);
            stopSelf();
        }
    }

    /**
     * @param userAction true 表示用户在界面上主动关闭/切换 —— 只有这种情形才清掉
     *                   Prefs.enabled，否则服务被系统杀掉或 VPN 被抢占后开机就不会自启了。
     */
    private void stopVpn(boolean userAction) {
        Stats.setRunning(false);
        if (userAction) Prefs.setEnabled(this, false);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
        worker = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        notifyState(false);
    }

    private void notifyState(boolean running) {
        try {
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(getPackageName());
            i.putExtra("running", running);
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "vpn revoked by system/other vpn");
        stopVpn(false);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn(false);
        super.onDestroy();
    }

    @Override
    public void protectSocket(DatagramSocket socket) {
        try {
            protect(socket);
        } catch (Exception e) {
            Log.w(TAG, "protect failed: " + e);
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "过滤服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        nb.setContentTitle("网络工具箱")
                .setContentText("正在为所选应用过滤广告域名")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setContentIntent(pi);
        startForeground(NOTIF_ID, nb.build());
    }
}
