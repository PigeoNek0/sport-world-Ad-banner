package com.zszl.netkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 开机自动恢复过滤（若上次处于开启状态）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) {
            return;
        }
        if (!Prefs.enabled(ctx)) {
            Log.i("NetKitBoot", "boot: disabled, skip");
            return;
        }
        try {
            Intent i = new Intent(ctx, AdVpnService.class);
            i.setAction(AdVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Log.w("NetKitBoot", "boot start failed: " + e);
        }
    }
}
