package com.zszl.netkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** 主界面：开关、应用选择、清单导入、统计。 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 1;
    private static final int REQ_IMPORT = 2;

    private Button btnMain;
    private Switch swExtra;
    private Switch swBig;
    private Switch swSkip;
    private Button btnA11y;
    /** 界面「期望状态」：按钮动作只依据它，绝不读 Stats —— 否则会与 onResume 刷新抢跑，
     *  出现「刚点开启却执行了 STOP」的竞态。 */
    private boolean wantOn;
    /** 已发出启停指令、等状态回灌；期间忽略后续点击，避免「刚开启又立刻关闭」。 */
    private boolean pending;
    /** 上次有效点击时刻（SystemClock.elapsedRealtime），做 1.5s 去抖。 */
    private long lastTapAt;
    private BroadcastReceiver stateRx;
    private TextView tvStatus;
    private TextView tvStats;
    private TextView tvTop;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        requestNotifPermission();
        wantOn = Stats.isRunning();
        stateRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                wantOn = i.getBooleanExtra("running", Stats.isRunning());
                refresh();
            }
        };
        IntentFilter f = new IntentFilter(AdVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateRx, f);
    }

    @Override
    protected void onDestroy() {
        if (stateRx != null) {
            try {
                unregisterReceiver(stateRx);
            } catch (Exception ignored) {
            }
            stateRx = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }


    /** 让内容避开状态栏：Android 15+ 默认边到边绘制，不处理 inset 会把标题压到状态栏下面。 */
    /** 浅色界面下把状态栏图标改成深色，否则白色图标看不见。 */
    private void lightStatusBar() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            getWindow().setStatusBarColor(0xFFF2F2F2);
        } catch (Throwable ignored) {
        }
    }

    private void applyTopInset(final android.view.View scroller, final android.view.View content, final int basePad) {
        scroller.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets ins) {
                int top = ins.getSystemWindowInsetTop();
                content.setPadding(basePad, basePad + top, basePad, basePad);
                return ins;
            }
        });
        scroller.requestApplyInsets();
    }

    private View buildUi() {
        lightStatusBar();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        applyTopInset(scroll, root, pad);

        TextView title = new TextView(this);
        title.setText("网络工具箱");
        title.setTextSize(22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("为选中的应用在本机拦掉广告域名，不修改任何 App，不走远程服务。");
        sub.setTextSize(13);
        root.addView(sub);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setPadding(0, pad, 0, pad);
        root.addView(tvStatus);

        btnMain = new Button(this);
        btnMain.setText("开启过滤");
        btnMain.setOnClickListener(v -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (pending) {
                android.util.Log.i("NetKitAct", "click ignored (pending)");
                return;
            }
            if (now - lastTapAt < 10000) {
                android.util.Log.i("NetKitAct", "click ignored (debounce " + (now - lastTapAt) + "ms, wantOn=" + wantOn + ")");
                return;
            }
            lastTapAt = now;
            pending = true;
            if (wantOn) {
                wantOn = false;
                stopVpn();
            } else {
                wantOn = true;
                startVpn();
            }
            refresh();
            // 兜底：6 秒内没等到状态广播就解除 pending，避免按钮永久禁用
            v.postDelayed(() -> {
                if (pending) {
                    pending = false;
                    refresh();
                }
            }, 6000);
        });
        root.addView(btnMain);

        swExtra = new Switch(this);
        swExtra.setText("同时拦截统计 / 推送上报（EXTRA 档）");
        swExtra.setChecked(Prefs.extra(this));
        swExtra.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setExtra(this, checked);
            if (Stats.isRunning()) reapply();
            refresh();
        });
        root.addView(swExtra);

        swBig = new Switch(this);
        swBig.setText("启用大清单（32 万条，拦得更狠，可能误伤业务）");
        swBig.setChecked(Prefs.big(this));
        swBig.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setBig(this, checked);
            if (Stats.isRunning()) reapply();
            refresh();
        });
        root.addView(swBig);

        swSkip = new Switch(this);
        swSkip.setText("自动跳过开屏 / 插屏（需无障碍权限）");
        swSkip.setChecked(Prefs.skip(this));
        swSkip.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setSkip(this, checked);
            if (checked && !SkipService.isEnabled(this)) {
                toast("请在无障碍设置里开启「网络工具箱（自动跳过）」");
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    toast("无法打开无障碍设置：" + e);
                }
            }
            refresh();
        });
        root.addView(swSkip);

        btnA11y = new Button(this);
        btnA11y.setText("重新授权无障碍（跳过功能用）");
        btnA11y.setOnClickListener(v -> {
            toast("在无障碍里找到「网络工具箱（自动跳过）」并打开");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("无法打开无障碍设置：" + e);
            }
        });
        root.addView(btnA11y);

        Button pick = new Button(this);
        pick.setText("选择要净化的应用");
        pick.setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
        root.addView(pick);

        Button imp = new Button(this);
        imp.setText("导入自定义清单（.txt）");
        imp.setOnClickListener(v -> pickImport());
        root.addView(imp);

        Button rules = new Button(this);
        rules.setText("编辑自定义规则");
        rules.setOnClickListener(v -> editRules());
        root.addView(rules);

        Button btnProf = new Button(this);
        btnProf.setText("管理坐标学习（人工复核）");
        btnProf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProfilesActivity.class));
            }
        });
        root.addView(btnProf);

        tvStats = new TextView(this);
        tvStats.setTextSize(15);
        tvStats.setPadding(0, pad, 0, 0);
        root.addView(tvStats);

        tvTop = new TextView(this);
        tvTop.setTextSize(13);
        tvTop.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(tvTop);

        Button refresh = new Button(this);
        refresh.setText("刷新统计");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        Button reset = new Button(this);
        reset.setText("清零统计");
        reset.setOnClickListener(v -> {
            Stats.reset();
            refresh();
        });
        root.addView(reset);

        TextView tip = new TextView(this);
        tip.setText("\n说明：系统同一时间只允许一个 VPN。开启后本应用会常驻通知栏；"
                + "关闭开关即停止。DNS 应答被改写为 0.0.0.0 / ::，"
                + "自渲染广告（不走域名请求的素材）无法被本方案拦掉。");
        tip.setTextSize(12);
        root.addView(tip);

        return scroll;
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
            } catch (Exception ignored) {
            }
        }
    }

    private void refresh() {
        boolean on = Stats.isRunning();
        if (on) wantOn = true;
        Set<String> pkgs = Prefs.packages(this);
        tvStatus.setText((on ? "● 正在过滤" : "○ 未启动")
                + "   目标应用 " + pkgs.size() + " 个");
        if (Prefs.skip(this)) {
            tvStatus.append(SkipService.isEnabled(this) ? "  ·跳过已启用" : "  ·跳过待授权");
        }
        if (btnA11y != null) {
            boolean need = Prefs.skip(this) && !SkipService.isEnabled(this);
            btnA11y.setVisibility(need ? View.VISIBLE : View.GONE);
        }
        tvStats.setText("本次运行已拦截 " + Stats.total() + " 次"
                + (Prefs.skip(this) ? "，已跳过广告 " + SkipService.firedCount + " 次" : ""));
        List<String> top = Stats.top(10);
        if (top.isEmpty()) {
            tvTop.setText("（暂无拦域名命中）");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String s : top) sb.append(s).append('\n');
            tvTop.setText(sb.toString());
        }
        btnMain.setText(wantOn ? "关闭过滤" : "开启过滤");
        // 服务状态已达成界面期望 → 解除 pending，按钮恢复可点
        if (pending && on == wantOn) pending = false;
        btnMain.setEnabled(!pending);
    }

    /** 配置变更：让服务原地重载过滤清单，不销毁服务。 */
    private void reapply() {
        android.util.Log.i("NetKitAct", "reapply filters");
        launchService(AdVpnService.ACTION_RELOAD);
    }

    private void startVpn() {
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        launchService(AdVpnService.ACTION_START);
    }

    private void stopVpn() {
        android.util.Log.i("NetKitAct", "stopVpn() at " + android.util.Log.getStackTraceString(new Throwable()));
        launchService(AdVpnService.ACTION_STOP);
    }

    private void launchService(String action) {
        Intent i = new Intent(this, AdVpnService.class);
        i.setAction(action);
        i.putExtra("from", "activity:" + action);
        // STOP 不能走 startForegroundService：服务若不进前台，Android 12+ 会抛
        // ForegroundServiceDidNotStartInTimeException 直接崩。
        if (AdVpnService.ACTION_START.equals(action) && Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                wantOn = true;
                launchService(AdVpnService.ACTION_START);
                refresh();
            } else {
                wantOn = false;
                refresh();
                toast("未授权 VPN，无法过滤");
            }
        } else if (req == REQ_IMPORT && res == RESULT_OK && data != null) {
            importFrom(data.getData());
        }
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void importFrom(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                toast("读取失败");
                return;
            }
            File dst = new File(getFilesDir(), "imported.txt");
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            in.close();
            out.close();
            toast("已导入 " + total + " 字节，重启过滤后生效");
            if (Stats.isRunning()) {
                stopVpn();
                startVpn();
            }
            refresh();
        } catch (Exception e) {
            toast("导入失败：" + e);
        }
    }

    private void editRules() {
        File f = new File(getFilesDir(), "rules.txt");
        String cur = "";
        try {
            if (f.isFile()) {
                byte[] all = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(all);
                in.close();
                cur = new String(all, "UTF-8");
            }
        } catch (Exception ignored) {
        }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur);
        et.setHint("每行一个域名，例如 ad.example.com");
        et.setMinLines(8);
        new AlertDialog.Builder(this)
                .setTitle("自定义规则")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(et.getText().toString().getBytes("UTF-8"));
                        out.close();
                        toast("已保存，重启过滤后生效");
                    } catch (Exception e) {
                        toast("保存失败：" + e);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
