package com.zszl.netkit;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地过滤清单。
 *
 * 装载顺序：assets/blocklist_core.txt（必装）→ assets/blocklist_extra.txt（可选）
 *          → files/imported.txt（用户导入）→ files/rules.txt（界面「编辑自定义规则」）。
 *
 * 匹配语义与 personalDNSfilter 一致：命中 qname 本身，或 qname 的任意父域命中，
 * 即视为拦截（例如清单里有 b.c，则 a.b.c 也会被拦）。
 */
public final class FilterList {

    private static final String TAG = "NetKitFilter";

    private final Set<String> hosts = new HashSet<String>();
    private volatile int size;

    public int size() {
        return size;
    }

    public void load(Context ctx, boolean withExtra, boolean withBig) {
        hosts.clear();
        loadAsset(ctx, "blocklist_core.txt");
        if (withExtra) loadAsset(ctx, "blocklist_extra.txt");
        if (withBig) loadAsset(ctx, "blocklist_big.txt");
        loadFile(new File(ctx.getFilesDir(), "imported.txt"));
        loadFile(new File(ctx.getFilesDir(), "rules.txt"));
        size = hosts.size();
        Log.i(TAG, "loaded " + size + " hosts (extra=" + withExtra + ", big=" + withBig + ")");
    }

    private void loadAsset(Context ctx, String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            readInto(in);
        } catch (Exception e) {
            Log.w(TAG, "asset miss " + name + ": " + e);
        }
    }

    private void loadFile(File f) {
        if (f == null || !f.isFile()) return;
        try {
            readInto(new FileInputStream(f));
        } catch (Exception e) {
            Log.w(TAG, "file miss " + f + ": " + e);
        }
    }

    private void readInto(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) addLine(line);
        } finally {
            r.close();
        }
    }

    private void addLine(String raw) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.length() == 0 || line.startsWith("#") || line.startsWith("//")) return;
        // 兼容 hosts 格式：0.0.0.0 domain / 127.0.0.1 domain
        if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") || line.startsWith("::")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) line = parts[1];
            else return;
        }
        // 兼容 >host 0.0.0.0 映射语法
        if (line.startsWith(">")) {
            String rest = line.substring(1).trim();
            String[] parts = rest.split("\\s+");
            if (parts.length >= 1) line = parts[0];
        }
        line = line.toLowerCase();
        if (line.endsWith(".")) line = line.substring(0, line.length() - 1);
        if (line.startsWith("*.")) line = line.substring(2);
        if (line.length() < 3 || line.indexOf('.') < 0) return;
        hosts.add(line);
    }

    /** 逐级上溯匹配：a.b.c → b.c → c。 */
    public boolean isBlocked(String qname) {
        if (qname == null) return false;
        String name = qname.toLowerCase();
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.length() == 0) return false;
        int idx = 0;
        while (true) {
            if (hosts.contains(name)) return true;
            idx = name.indexOf('.');
            if (idx < 0 || idx + 1 >= name.length()) return false;
            name = name.substring(idx + 1);
        }
    }

    public void addCustom(List<String> lines) {
        for (String l : lines) addLine(l);
        size = hosts.size();
    }
}
