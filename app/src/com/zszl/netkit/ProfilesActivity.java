package com.zszl.netkit;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 坐标学习档案的人工复核页：逐条启用 / 停用 / 删除。
 *
 * 学习样本默认是「待复核」，只有人工点「启用」或累计 2 次自动确认之后才会被
 * SkipService 使用；检测到跳转的样本会被 SkipService 直接置为「停用」。
 */
public class ProfilesActivity extends Activity {

    private LinearLayout box;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lightStatusBar();
        ScrollView sc = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        sc.addView(box);
        applyTopInset(sc, box, pad);
        setContentView(sc);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }


    /** 让内容避开状态栏：Android 15+ 默认边到边绘制，不处理 inset 会把标题压到状态栏下面。 */
    /** 浅色界面下把状态栏图标改成深色，否则白色图标看不见。 */
    private void lightStatusBar() {
        Ui.applyStatusBar(this);
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

    private void render() {
        box.removeAllViews();

        TextView title = new TextView(this);
        title.setText("坐标学习档案");
        title.setTextSize(22);
        title.setTextColor(Ui.c(this, R.color.text_primary));
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("只记录「自己点完广告真的消失」的样本。默认待复核，需要你确认后才会自动套用；"
                + "若某次点击导致跳到别的 App，该样本会被自动停用。");
        hint.setTextSize(13);
        hint.setTextColor(Ui.c(this, R.color.text_secondary));
        hint.setPadding(0, 8, 0, 16);
        box.addView(hint);

        List<Profiles.Item> items = Profiles.list(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无样本。多点几次广告的关闭 / 跳过，成功后会出现在这里。");
            empty.setTextSize(14);
            box.addView(empty);
        }

        for (final Profiles.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 12, 0, 12);

            TextView name = new TextView(this);
            name.setText(it.sig);
            name.setTextSize(15);
            name.setTextColor(Ui.c(this, R.color.text_primary));
            row.addView(name);

            TextView meta = new TextView(this);
            meta.setText(it.label());
            meta.setTextSize(12);
            meta.setTextColor(Ui.c(this, "off".equals(it.state) ? R.color.text_disabled : R.color.text_secondary));
            row.addView(meta);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER_VERTICAL);

            Button on = new Button(this);
            on.setText("启用");
            on.setEnabled(!"auto".equals(it.state));
            on.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Profiles.setState(ProfilesActivity.this, it.sig, "auto");
                    Toast.makeText(ProfilesActivity.this, "已启用：" + it.sig, Toast.LENGTH_SHORT).show();
                    render();
                }
            });
            btns.addView(on);

            Button off = new Button(this);
            off.setText("停用");
            off.setEnabled(!"off".equals(it.state));
            off.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Profiles.setState(ProfilesActivity.this, it.sig, "off");
                    render();
                }
            });
            btns.addView(off);

            Button del = new Button(this);
            del.setText("删除");
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Profiles.remove(ProfilesActivity.this, it.sig);
                    render();
                }
            });
            btns.addView(del);

            row.addView(btns);
            box.addView(row);
        }

        Button clear = new Button(this);
        clear.setText("清空全部学习记录");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Profiles.clear(ProfilesActivity.this);
                render();
            }
        });
        box.addView(clear);
    }
}
