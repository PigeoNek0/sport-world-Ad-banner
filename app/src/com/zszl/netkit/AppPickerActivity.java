package com.zszl.netkit;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 选择要净化的应用：搜索 + 分类筛选 + 排序 + 已选置顶。 */
public class AppPickerActivity extends Activity {

    // 分类（下标即 Spinner 顺序，见 Q23）
    private static final int CAT_ALL = 0;
    private static final int CAT_EXT = 1;   // 外部应用：非系统 + 有启动图标
    private static final int CAT_SYS = 2;   // 系统应用：系统 + 有启动图标
    private static final int CAT_CMP = 3;   // 系统组件：无启动图标

    private static final int SORT_NAME = 0;
    private static final int SORT_INSTALL = 1;
    private static final int SORT_USAGE = 2;

    private static final String SP_KEY = "picker_prefs";

    private static final class Item {
        String pkg;
        String label;
        boolean system;
        boolean launcher;
        boolean hasNet;
        boolean vpn;
        long firstInstall;
        long lastUsed;
        boolean checked;

        int cat() {
            if (!launcher) return CAT_CMP;
            return system ? CAT_SYS : CAT_EXT;
        }
    }

    private final List<Item> all = new ArrayList<Item>();
    private final List<Item> shown = new ArrayList<Item>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView emptyView;
    private TextView usageHint;
    private int effSort = SORT_NAME;

    private EditText search;
    private Spinner catSpin;
    private Spinner sortSpin;
    private Switch netSw;
    private Switch pinSw;
    private TextView summary;
    private TextView loading;
    private ListView list;
    private RowAdapter adapter;

    private int cat = CAT_ALL;
    private int sort = SORT_NAME;
    private boolean onlyNet = true;
    private boolean pinSelected = true;
    private String query = "";
    private boolean usageAllowed;
    private boolean loadingDone;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(SP_KEY, Context.MODE_PRIVATE);
        sort = sp.getInt("sort", SORT_NAME);
        pinSelected = sp.getBoolean("pin", true);
        onlyNet = sp.getBoolean("net", true);

        Ui.applyStatusBar(this);
        setContentView(buildSkeleton());
        loadAppsAsync();
    }

    /* ---------- UI ---------- */

    private View buildSkeleton() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Ui.c(this, R.color.bg));

        TextView title = new TextView(this);
        title.setText("选择要净化的应用");
        title.setTextSize(20);
        title.setTextColor(Ui.c(this, R.color.text_primary));
        title.setPadding(pad, pad, pad, 0);
        wrap.addView(title);

        TextView tip = new TextView(this);
        tip.setText("勾选后返回即生效；未勾选的应用完全不受影响。灰色条目是 VPN 类应用，不能同时启用。");
        tip.setTextSize(12);
        tip.setTextColor(Ui.c(this, R.color.text_secondary));
        tip.setPadding(pad, pad / 2, pad, pad / 2);
        wrap.addView(tip);

        // 搜索行：输入框 + 分类 Spinner
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(pad, 0, pad, 0);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名或包名");
        search.setHintTextColor(Ui.c(this, R.color.text_disabled));
        search.setTextColor(Ui.c(this, R.color.text_primary));
        search.setTextSize(14);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lpSearch = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchRow.addView(search, lpSearch);

        catSpin = new Spinner(this);
        catSpin.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>()));
        LinearLayout.LayoutParams lpSpin = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSpin.leftMargin = pad / 2;
        searchRow.addView(catSpin, lpSpin);
        wrap.addView(searchRow);

        // 设置行：只看有网络 + 排序 + 已选置顶
        LinearLayout setRow = new LinearLayout(this);
        setRow.setOrientation(LinearLayout.HORIZONTAL);
        setRow.setPadding(pad, 0, pad, 0);
        setRow.setGravity(Gravity.CENTER_VERTICAL);

        netSw = new Switch(this);
        netSw.setText("只看有网络");
        netSw.setTextSize(12);
        netSw.setTextColor(Ui.c(this, R.color.text_secondary));
        netSw.setChecked(onlyNet);
        setRow.addView(netSw);

        sortSpin = new Spinner(this);
        sortSpin.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"按名称", "按安装时间", "按最近使用"}));
        LinearLayout.LayoutParams lpSort = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpSort.leftMargin = pad / 2;
        setRow.addView(sortSpin, lpSort);

        pinSw = new Switch(this);
        pinSw.setText("已选置顶");
        pinSw.setTextSize(12);
        pinSw.setTextColor(Ui.c(this, R.color.text_secondary));
        pinSw.setChecked(pinSelected);
        setRow.addView(pinSw);
        wrap.addView(setRow);

        // 统计行
        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(Ui.c(this, R.color.text_secondary));
        summary.setPadding(pad, pad / 2, pad, pad / 2);
        wrap.addView(summary);

        loading = new TextView(this);
        loading.setText("正在读取应用列表…");
        loading.setTextSize(12);
        loading.setTextColor(Ui.c(this, R.color.text_disabled));
        loading.setPadding(pad, 0, pad, pad / 2);
        wrap.addView(loading);

        // 用量权限提示（Q11=a）：选了「按最近使用」但没授权时出现，点一下跳系统设置
        usageHint = new TextView(this);
        usageHint.setTextSize(12);
        usageHint.setTextColor(Ui.c(this, R.color.text_secondary));
        usageHint.setPadding(pad, pad / 4, pad, pad / 2);
        usageHint.setVisibility(View.GONE);
        usageHint.setText("「按最近使用」需要『使用情况访问』权限 — 点此去授权（不授权则按安装时间排）");
        usageHint.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "打不开系统设置，请手动到 设置-应用-特殊权限-使用情况访问", Toast.LENGTH_LONG).show();
            }
        });
        wrap.addView(usageHint);

        list = new ListView(this);
        list.setDividerHeight(1);
        list.setBackgroundColor(Ui.c(this, R.color.bg));
        adapter = new RowAdapter();
        list.setAdapter(adapter);

        emptyView = new TextView(this);
        emptyView.setTextSize(14);
        emptyView.setTextColor(Ui.c(this, R.color.text_secondary));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(pad, pad, pad, pad);
        emptyView.setVisibility(View.GONE);
        emptyView.setOnClickListener(v -> {
            // 一键回到默认筛选（Q18 口径）
            search.setText("");
            query = "";
            cat = CAT_ALL;
            onlyNet = true;
            netSw.setChecked(true);
            sp.edit().putBoolean("net", true).apply();
            refresh();
        });

        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.addView(list, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 键盘弹起时屏幕中下部会被挡住，空态提示贴顶放才不会看不见
        android.widget.FrameLayout.LayoutParams lpEmpty =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP);
        lpEmpty.topMargin = (int) (48 * d);
        holder.addView(emptyView, lpEmpty);
        wrap.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // inset 加在整容器上，标题才不会跑到状态栏下面
        wrap.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets ins) {
                v.setPadding(0, ins.getSystemWindowInsetTop(), 0, 0);
                return ins;
            }
        });

        // 事件
        search.addTextChangedListener(new TextWatcher() {
            final Runnable pending = new Runnable() {
                @Override
                public void run() {
                    query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                    refresh();
                }
            };

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(pending);
                ui.postDelayed(pending, 200);
            }
        });

        catSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (cat != pos) {
                    cat = pos;
                    refresh();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        sortSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (sort != pos) {
                    sort = pos;
                    sp.edit().putInt("sort", sort).apply();
                    refresh();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        netSw.setOnCheckedChangeListener((v, c) -> {
            onlyNet = c;
            sp.edit().putBoolean("net", c).apply();
            refresh();
        });

        pinSw.setOnCheckedChangeListener((v, c) -> {
            pinSelected = c;
            sp.edit().putBoolean("pin", c).apply();
            refresh();
        });

        return wrap;
    }

    /* ---------- 数据 ---------- */

    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> loaded = new ArrayList<Item>();
                usageAllowed = hasUsageAccess();
                Map<String, UsageStats> usage = null;
                if (usageAllowed) {
                    try {
                        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                        long now = System.currentTimeMillis();
                        usage = usm.queryAndAggregateUsageStats(now - 7L * 24 * 3600 * 1000, now);
                    } catch (Throwable ignored) {
                    }
                }

                PackageManager pm = getPackageManager();
                Set<String> selected = Prefs.packages(AppPickerActivity.this);
                Set<String> launcherPkgs = launcherPackages(pm);
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo ai : apps) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    Item it = new Item();
                    it.pkg = ai.packageName;
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    it.launcher = launcherPkgs.contains(it.pkg);
                    it.checked = selected.contains(it.pkg);
                    it.lastUsed = 0L;
                    if (usage != null) {
                        UsageStats u = usage.get(it.pkg);
                        if (u != null) it.lastUsed = u.getLastTimeUsed();
                    }
                    try {
                        PackageInfo pi = pm.getPackageInfo(it.pkg,
                                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
                        it.firstInstall = pi.firstInstallTime;
                        if (pi.requestedPermissions != null) {
                            for (String perm : pi.requestedPermissions) {
                                if ("android.permission.INTERNET".equals(perm)) {
                                    it.hasNet = true;
                                    break;
                                }
                            }
                        }
                        if (pi.services != null) {
                            for (ServiceInfo si : pi.services) {
                                if ("android.permission.BIND_VPN_SERVICE".equals(si.permission)) {
                                    it.vpn = true;
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    loaded.add(it);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(loaded);
                        loadingDone = true;
                        loading.setVisibility(View.GONE);
                        refresh();
                    }
                });
            }
        }, "netkit-picker-load").start();
    }

    private Set<String> launcherPackages(PackageManager pm) {
        Set<String> out = new HashSet<String>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> ris =
                    pm.queryIntentActivities(i, PackageManager.MATCH_ALL);
            for (android.content.pm.ResolveInfo ri : ris) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------- 过滤 / 排序 / 渲染 ---------- */

    private void refresh() {
        if (!loadingDone) return;

        // 分类数量：先应用「只看有网络」，再按分类统计（Q19/Q20）
        int[] counts = new int[4];
        for (Item it : all) {
            if (onlyNet && !it.hasNet) continue;
            counts[CAT_ALL]++;
            counts[it.cat()]++;
        }
        ((ArrayAdapter<String>) catSpin.getAdapter()).clear();
        ArrayAdapter<String> ca = (ArrayAdapter<String>) catSpin.getAdapter();
        ca.add("全部 " + counts[CAT_ALL]);
        ca.add("外部应用 " + counts[CAT_EXT]);
        ca.add("系统应用 " + counts[CAT_SYS]);
        ca.add("系统组件 " + counts[CAT_CMP]);
        ca.notifyDataSetChanged();
        catSpin.setSelection(Math.min(cat, 3));

        // 列表
        shown.clear();
        for (Item it : all) {
            if (onlyNet && !it.hasNet) continue;
            if (cat != CAT_ALL && it.cat() != cat) continue;
            if (!query.isEmpty()) {
                String l = it.label.toLowerCase(Locale.ROOT);
                if (!l.contains(query) && !it.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            }
            shown.add(it);
        }
        effSort = (sort == SORT_USAGE && !usageAllowed) ? SORT_INSTALL : sort;
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(shown, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (pinSelected && a.checked != b.checked) return a.checked ? -1 : 1;
                if (effSort == SORT_INSTALL) return Long.compare(b.firstInstall, a.firstInstall);
                if (effSort == SORT_USAGE) return Long.compare(b.lastUsed, a.lastUsed);
                return col.compare(a.label, b.label);
            }
        });

        // 统计行：已选 / 无网络已选 / 被当前筛选隐藏的已选数量
        int selTotal = 0, selNoNet = 0, hidden = 0;
        for (Item it : all) {
            if (it.checked) {
                selTotal++;
                if (!it.hasNet) selNoNet++;
            }
        }
        for (Item it : all) {
            if (it.checked && !shown.contains(it)) hidden++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已选 ").append(selTotal).append(" 个");
        if (selNoNet > 0) sb.append("（含 ").append(selNoNet).append(" 个无网络）");
        if (hidden > 0) sb.append(" · 当前筛选隐藏了 ").append(hidden).append(" 个已选");
        sb.append(" · 显示 ").append(shown.size()).append(" 个");
        summary.setText(sb.toString());

        usageHint.setVisibility((sort == SORT_USAGE && !usageAllowed) ? View.VISIBLE : View.GONE);

        if (shown.isEmpty()) {
            emptyView.setText("没有匹配的应用\n点这里清除搜索与筛选条件");
            emptyView.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }

        adapter.notifyDataSetChanged();
    }

    private final class RowAdapter extends ArrayAdapter<Item> {
        RowAdapter() {
            super(AppPickerActivity.this, 0, shown);
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Item getItem(int pos) {
            return shown.get(pos);
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            CheckBox cb;
            TextView sub;
            if (convert == null) {
                float d = getResources().getDisplayMetrics().density;
                int pad = (int) (12 * d);
                row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(pad, pad / 2, pad, pad / 2);
                row.setBackgroundColor(Ui.c(AppPickerActivity.this, R.color.bg));

                cb = new CheckBox(AppPickerActivity.this);
                cb.setTextSize(14);
                cb.setTextColor(Ui.c(AppPickerActivity.this, R.color.text_primary));
                row.addView(cb);

                sub = new TextView(AppPickerActivity.this);
                sub.setTextSize(11);
                sub.setTextColor(Ui.c(AppPickerActivity.this, R.color.text_secondary));
                sub.setPadding((int) (32 * d), 0, 0, 0);
                row.addView(sub);

                row.setTag(new Object[]{cb, sub});
                cb.setTag(row);
            } else {
                row = (LinearLayout) convert;
                Object[] tags = (Object[]) row.getTag();
                cb = (CheckBox) tags[0];
                sub = (TextView) tags[1];
            }

            final Item it = shown.get(pos);
            cb.setOnCheckedChangeListener(null);
            cb.setText(it.label);
            cb.setChecked(it.checked);
            cb.setEnabled(!it.vpn);
            cb.setTextColor(Ui.c(AppPickerActivity.this, it.vpn ? R.color.text_disabled : R.color.text_primary));
            cb.setOnCheckedChangeListener((v, c) -> {
                it.checked = c;
                refresh();
            });

            StringBuilder meta = new StringBuilder(it.pkg);
            if (it.pkg.equals(Prefs.DEFAULT_TARGET)) meta.append(" · 默认目标");
            if (!it.hasNet) meta.append(" · 无网络权限");
            if (it.vpn) meta.append(" · VPN 类，不能同时启用");
            sub.setText(meta.toString());
            return row;
        }
    }

    /* ---------- 保存 ---------- */

    @Override
    protected void onPause() {
        super.onPause();
        // 保留磁盘上「已安装但本次未列出」的旧条目，避免误丢（R15）
        Set<String> installed = new HashSet<String>();
        for (Item it : all) installed.add(it.pkg);
        Set<String> out = new LinkedHashSet<String>();
        for (String p : Prefs.packages(this)) {
            if (!installed.contains(p)) out.add(p);
        }
        for (Item it : all) {
            if (it.checked && !it.vpn) out.add(it.pkg);
        }
        if (out.isEmpty()) out.add(Prefs.DEFAULT_TARGET);
        boolean changed = !out.equals(Prefs.packages(this));
        Prefs.setPackages(this, out);
        android.util.Log.i("NetKitPick", "onPause, changed=" + changed
                + " running=" + Stats.isRunning() + " size=" + out.size());
        if (changed && Stats.isRunning()) {
            Intent i = new Intent(this, AdVpnService.class);
            i.setAction(AdVpnService.ACTION_RELOAD);
            i.putExtra("from", "picker");
            startService(i);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「授予用量权限」页面回来时重算排序可用性
        if (loadingDone) {
            boolean now = hasUsageAccess();
            if (now != usageAllowed) {
                usageAllowed = now;
                Toast.makeText(this, now ? "已获得用量权限，可按最近使用排序" : "没有用量权限，按最近使用排序不可用",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
