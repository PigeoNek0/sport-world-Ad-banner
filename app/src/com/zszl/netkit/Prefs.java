package com.zszl.netkit;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** 轻量配置存储。 */
public final class Prefs {

    private static final String FILE = "netkit";
    private static final String K_ENABLED = "enabled";
    private static final String K_EXTRA = "extra";
    private static final String K_PKGS = "pkgs";
    private static final String K_STOP_ON_EXIT = "stop_on_exit";
    private static final String K_UPSTREAM = "upstream";

    public static final String DEFAULT_TARGET = "com.zjwh.android_wh_physicalfitness";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ENABLED, v).apply();
    }

    /** 是否加载 EXTRA 档（统计/推送上报）。 */
    public static boolean extra(Context c) {
        return sp(c).getBoolean(K_EXTRA, false);
    }

    public static void setExtra(Context c, boolean v) {
        sp(c).edit().putBoolean(K_EXTRA, v).apply();
    }

    /** 是否加载大清单（32 万条，来自 pDNSf 合并清单裁剪）。 */
    public static boolean big(Context c) {
        return sp(c).getBoolean("big", false);
    }

    public static void setBig(Context c, boolean v) {
        sp(c).edit().putBoolean("big", v).apply();
    }

    /** 是否启用无障碍自动跳过开屏/插屏（默认关，需用户在系统里授权）。 */
    public static boolean skip(Context c) {
        return sp(c).getBoolean("skip", false);
    }

    public static void setSkip(Context c, boolean v) {
        sp(c).edit().putBoolean("skip", v).apply();
    }

    /** 学习到的坐标是否需要人工复核后才生效（默认开启，避免自动误点）。 */
    public static boolean review(Context c) {
        return sp(c).getBoolean("review", true);
    }

    public static void setReview(Context c, boolean v) {
        sp(c).edit().putBoolean("review", v).apply();
    }

    public static boolean stopOnExit(Context c) {
        return sp(c).getBoolean(K_STOP_ON_EXIT, false);
    }

    public static void setStopOnExit(Context c, boolean v) {
        sp(c).edit().putBoolean(K_STOP_ON_EXIT, v).apply();
    }

    /** 自定义上游 DNS（逗号分隔），空表示自动探测。 */
    public static String upstream(Context c) {
        return sp(c).getString(K_UPSTREAM, "");
    }

    public static void setUpstream(Context c, String v) {
        sp(c).edit().putString(K_UPSTREAM, v == null ? "" : v).apply();
    }

    /** 被净化的应用包名集合；默认只有目标应用。 */
    public static Set<String> packages(Context c) {
        String raw = sp(c).getString(K_PKGS, DEFAULT_TARGET);
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (t.length() > 0) out.add(t);
            }
        }
        if (out.isEmpty()) out.add(DEFAULT_TARGET);
        return out;
    }

    public static void setPackages(Context c, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        sp(c).edit().putString(K_PKGS, sb.toString()).apply();
    }
}
