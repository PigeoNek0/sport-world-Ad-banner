package com.zszl.netkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 进程内统计：总拦截数 + 按域名计数（用于 Top 列表）。 */
public final class Stats {

    private static final AtomicLong TOTAL = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> PER_HOST =
            new ConcurrentHashMap<String, AtomicLong>();
    private static volatile boolean running;

    private Stats() {}

    public static void onBlocked(String host) {
        TOTAL.incrementAndGet();
        AtomicLong c = PER_HOST.get(host);
        if (c == null) {
            c = new AtomicLong();
            AtomicLong prev = PER_HOST.putIfAbsent(host, c);
            if (prev != null) c = prev;
        }
        c.incrementAndGet();
    }

    public static long total() {
        return TOTAL.get();
    }

    public static void reset() {
        TOTAL.set(0);
        PER_HOST.clear();
    }

    public static void setRunning(boolean v) {
        running = v;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 返回形如 "ad.toutiao.com 12" 的前 n 行。 */
    public static List<String> top(int n) {
        List<Map.Entry<String, AtomicLong>> all =
                new ArrayList<Map.Entry<String, AtomicLong>>(PER_HOST.entrySet());
        Collections.sort(all, new Comparator<Map.Entry<String, AtomicLong>>() {
            @Override
            public int compare(Map.Entry<String, AtomicLong> a, Map.Entry<String, AtomicLong> b) {
                return Long.compare(b.getValue().get(), a.getValue().get());
            }
        });
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < all.size() && i < n; i++) {
            out.add(all.get(i).getKey() + "  ×" + all.get(i).getValue().get());
        }
        return out;
    }
}
