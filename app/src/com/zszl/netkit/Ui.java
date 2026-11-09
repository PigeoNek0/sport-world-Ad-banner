package com.zszl.netkit;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;

/** 统一的状态栏 / 配色工具：跟随系统深浅色。 */
final class Ui {

    private Ui() {
    }

    static boolean isNight(Activity a) {
        int mode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 状态栏底色跟主题背景一致；浅色底用深色图标，深色底用浅色图标。 */
    static void applyStatusBar(Activity a) {
        try {
            int bg = a.getResources().getColor(R.color.bg);
            boolean night = isNight(a);
            a.getWindow().setStatusBarColor(bg);
            View decor = a.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    static int c(Activity a, int resId) {
        return a.getResources().getColor(resId);
    }

    static int alpha(int color, float f) {
        return Color.argb((int) (255 * f), Color.red(color), Color.green(color), Color.blue(color));
    }
}
