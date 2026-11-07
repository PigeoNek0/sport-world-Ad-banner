package com.zszl.netkit;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐标学习档案（签名 → 相对坐标 + 命中/失误计数 + 状态）。
 *
 * 状态机：
 *   pending —— 刚学到（或人工未复核），不会自动使用；
 *   auto    —— 自动生效（hits>=2 且未被判为跳转）；
 *   off     —— 人工停用，或检测到跳转后被硬性禁用。
 *
 * 之所以要有 pending：学习样本来自「我们自己点击成功」，但仍可能因为
 * 分辨率差异/版式变化导致坐标偏移，因此默认允许人工逐条复核后再启用。
 */
public class Profiles {

    public static class Item {
        public String sig;
        public float x, y;
        public int hits, misses;
        public String state = "pending";

        public Item(String sig) {
            this.sig = sig;
        }

        public boolean active() {
            return "auto".equals(state) && hits >= 2;
        }

        public String label() {
            String st = "auto".equals(state) ? "生效" : ("off".equals(state) ? "停用" : "待复核");
            return st + " · 命中" + hits + " 失误" + misses
                    + " · (" + String.format("%.3f", x) + ", " + String.format("%.3f", y) + ")";
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "profiles.json");
    }

    /** 人工复核状态单独存一个文件：学习流程只写 profiles.json，永不触碰这里，
     *  这样「已启用 / 已停用」就不会被自动学习覆盖回 pending。 */
    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), "profile_states.json");
    }

    public static Map<String, String> loadStates(Context c) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        try {
            File f = stateFile(c);
            if (!f.exists()) return m;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k, "pending"));
            }
        } catch (Throwable t) {
            android.util.Log.w("NetKitSkip", "states load failed: " + t);
        }
        return m;
    }

    /** 原子写：先写 .tmp 再 rename，避免读到半截 JSON（实测曾把内存档案清空过）。 */
    private static void writeAtomic(java.io.File f, String data) throws java.io.IOException {
        java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".tmp");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
        try {
            fo.write(data.getBytes("UTF-8"));
            fo.flush();
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(f)) {
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed: " + tmp);
        }
    }

    public static void saveStates(Context c, Map<String, String> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            writeAtomic(stateFile(c), o.toString());
        } catch (Throwable t) {
            android.util.Log.w("NetKitSkip", "states save failed: " + t);
        }
    }

    public static Map<String, Item> load(Context c) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        try {
            File f = file(c);
            if (!f.exists()) return map;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null) continue;
                Item item = new Item(k);
                if (a.length() >= 5) {                 // 新格式：[x,y,hits,misses,"state"]
                    item.x = (float) a.optDouble(0);
                    item.y = (float) a.optDouble(1);
                    item.hits = (int) a.optDouble(2);
                    item.misses = (int) a.optDouble(3);
                    item.state = a.optString(4, "pending");
                } else if (a.length() >= 4) {          // 旧格式：无 state
                    item.x = (float) a.optDouble(0);
                    item.y = (float) a.optDouble(1);
                    item.hits = (int) a.optDouble(2);
                    item.misses = (int) a.optDouble(3);
                    item.state = item.hits >= 2 ? "auto" : "pending";
                } else {
                    continue;
                }
                map.put(k, item);
            }
        } catch (Throwable t) {
            android.util.Log.w("NetKitSkip", "profiles load failed: " + t);
        }
        return map;
    }

    public static void save(Context c, Map<String, Item> map) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Item> e : map.entrySet()) {
                Item it = e.getValue();
                JSONArray a = new JSONArray();
                a.put((double) it.x);
                a.put((double) it.y);
                a.put(it.hits);
                a.put(it.misses);
                a.put(it.state);
                o.put(e.getKey(), a);
            }
            writeAtomic(file(c), o.toString());
        } catch (Throwable t) {
            android.util.Log.w("NetKitSkip", "profiles save failed: " + t);
        }
    }

    public static List<Item> list(Context c) {
        Map<String, Item> m = load(c);
        Map<String, String> st = loadStates(c);
        for (Map.Entry<String, Item> e : m.entrySet()) {
            String s2 = st.get(e.getKey());
            if (s2 != null) e.getValue().state = s2;
        }
        return new ArrayList<Item>(m.values());
    }

    public static void setState(Context c, String sig, String state) {
        Map<String, String> m = loadStates(c);
        m.put(sig, state);
        saveStates(c, m);
        // 同时把 hits 抬到可信阈值（否则 active() 仍不成立）
        if ("auto".equals(state)) {
            Map<String, Item> items = load(c);
            Item it = items.get(sig);
            if (it != null && it.hits < 2) {
                it.hits = 2;
                save(c, items);
            }
        }
    }

    public static void remove(Context c, String sig) {
        Map<String, Item> m = load(c);
        m.remove(sig);
        save(c, m);
        Map<String, String> st = loadStates(c);
        st.remove(sig);
        saveStates(c, st);
    }

    public static void clear(Context c) {
        save(c, new LinkedHashMap<String, Item>());
        saveStates(c, new LinkedHashMap<String, String>());
    }
}
