package com.zszl.netkit;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 选择要净化的应用（默认已选运动世界）。 */
public class AppPickerActivity extends Activity {

    private static final class Entry {
        String pkg;
        String label;
        boolean checked;
    }

    private final List<Entry> items = new ArrayList<Entry>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Set<String> selected = Prefs.packages(this);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            Entry e = new Entry();
            e.pkg = ai.packageName;
            e.label = String.valueOf(pm.getApplicationLabel(ai));
            e.checked = selected.contains(e.pkg);
            items.add(e);
        }
        Collections.sort(items, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.checked != b.checked) return a.checked ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        Ui.applyStatusBar(this);
        final ListView lv = new ListView(this);
        final int basePad = (int) (8 * getResources().getDisplayMetrics().density);
        lv.setClipToPadding(false);
        lv.setAdapter(new ArrayAdapter<Entry>(this, 0, items) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 12);
                row.setPadding(pad, pad / 2, pad, pad / 2);
                Entry e = items.get(pos);

                CheckBox cb = new CheckBox(AppPickerActivity.this);
                cb.setText(e.label + "  (" + e.pkg + ")");
                cb.setChecked(e.checked);
                cb.setTextSize(14);
                cb.setOnCheckedChangeListener((v, c) -> e.checked = c);
                row.addView(cb);

                TextView hint = new TextView(AppPickerActivity.this);
                hint.setText(e.pkg.equals(Prefs.DEFAULT_TARGET) ? "默认目标：运动世界" : "");
                hint.setTextSize(11);
                row.addView(hint);
                return row;
            }
        });
        // 去掉了系统 ActionBar，这里补一个标题栏，否则页面没有上下文
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView ttl = new android.widget.TextView(this);
        ttl.setText("选择要净化的应用");
        ttl.setTextSize(20);
        ttl.setPadding(basePad * 2, basePad * 2, basePad * 2, 0);
        wrap.addView(ttl);
        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("勾选后返回即生效；同一时间只允许一个 VPN，未勾选的应用不受影响。");
        tip.setTextSize(12);
        tip.setPadding(basePad * 2, basePad, basePad * 2, basePad);
        wrap.addView(tip);
        wrap.addView(lv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // inset 要加在「整个容器」上，加到 ListView 上会让标题跑到状态栏底下
        wrap.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets ins) {
                v.setPadding(0, ins.getSystemWindowInsetTop(), 0, 0);
                return ins;
            }
        });
        setContentView(wrap);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Set<String> out = new LinkedHashSet<String>();
        for (Entry e : items) if (e.checked) out.add(e.pkg);
        if (out.isEmpty()) out.add(Prefs.DEFAULT_TARGET);
        boolean changed = !out.equals(Prefs.packages(this));
        Prefs.setPackages(this, out);
        android.util.Log.i("NetKitPick", "onPause, changed=" + changed + " running=" + Stats.isRunning());
        if (changed && Stats.isRunning()) {
            // 目标变化 → 原地重建 VPN（RELOAD 会重新读 Prefs.packages）。
            // 不能发 ACTION_STOP：那是「用户主动关闭」，会清掉 Prefs.enabled 且不会再拉起。
            android.content.Intent i = new android.content.Intent(this, AdVpnService.class);
            i.setAction(AdVpnService.ACTION_RELOAD);
            i.putExtra("from", "picker");
            startService(i);
        }
    }
}
