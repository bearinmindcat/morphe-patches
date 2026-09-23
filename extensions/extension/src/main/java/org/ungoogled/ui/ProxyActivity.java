package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * "Proxy" screen, reached from Customization -> Proxy.  Laid out like OsmAnd's
 * proxy settings and styled like the Customization screen: an Enable toggle,
 * then Proxy host and Proxy port rows whose summary is the value or "None".
 * Every change is folded into the one setting Maps' traffic actually uses, so
 * the parts survive being switched off and back on. Leaving the screen with
 * that setting changed restarts Maps, because not every network stack picks a
 * change up mid-process (see Shapes.applyProxy). Covers this app's own traffic
 * only, Google's endpoints included; Play services runs elsewhere and is untouched.
 */
public final class ProxyActivity extends Activity {
    private static final int BG = 0xFFFFFFFF, TEXT = 0xFF1B1B1F, SUMMARY = 0xFF5F6368;
    private static final int BG_D = 0xFF131314, TEXT_D = 0xFFE3E3E3, SUMMARY_D = 0xFFC4C7C5;
    private boolean dark, black;
    private int bg() { return black ? 0xFF000000 : dark ? BG_D : BG; }
    private int text() { return dark ? TEXT_D : TEXT; }
    private int summary() { return dark ? SUMMARY_D : SUMMARY; }

    private Switch enable;
    private String initial;          // the effective setting when the screen opened
    private TextView hostSub, portSub;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Shapes.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initial = Shapes.proxy(this);
        black = Shapes.BLACK;
        String darkMode = getSharedPreferences("settings_preference", MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        boolean systemNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = black || "ON".equals(darkMode) || (!"OFF".equals(darkMode) && systemNight);
        setTitle("Proxy");
        if (getActionBar() != null) getActionBar().hide();
        getWindow().getDecorView().setBackgroundColor(bg());
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sb = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText("Proxy");
        title.setTextColor(text());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        header.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        ImageView close = new ImageView(this);
        int closeRes = getResources().getIdentifier("gs_close_vd_theme_24", "drawable", getPackageName());
        close.setImageResource(closeRes != 0 ? closeRes : android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(text());
        close.setContentDescription("Close");
        close.setOnClickListener(v -> finish());
        header.addView(close, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(24));

        enable = new Switch(this);
        enable.setChecked(Shapes.proxyOn(this));
        body.addView(toggleRow("Enable proxy", null, enable));
        enable.setOnCheckedChangeListener((CompoundButton b, boolean on) -> save());

        LinearLayout hostRow = rowBase("Proxy host", "None");
        hostSub = (TextView) ((LinearLayout) hostRow.getChildAt(0)).getChildAt(1);
        hostRow.setOnClickListener(v -> ask("Proxy host", "e.g. 127.0.0.1 (Orbot)", Shapes.proxyHost(this), false));
        body.addView(hostRow);

        LinearLayout portRow = rowBase("Proxy port", "None");
        portSub = (TextView) ((LinearLayout) portRow.getChildAt(0)).getChildAt(1);
        portRow.setOnClickListener(v -> {
            int p = Shapes.proxyPort(this);
            ask("Proxy port", "e.g. 8118 (Orbot HTTP)", p > 0 ? String.valueOf(p) : "", true);
        });
        body.addView(portRow);

        if (!CronetProxy.supported) {
            // An engine in this process could not take the proxy (see CronetProxy): say so where it is set.
            TextView note = new TextView(this);
            note.setText("This phone's network engine (Play services' Cronet) is too old to use a proxy, "
                    + "so most of Maps' traffic goes direct.");
            note.setTextColor(summary());
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            note.setPadding(dp(20), dp(16), dp(20), 0);
            body.addView(note);
        }

        refresh();
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && initial != null && !initial.equals(Shapes.proxy(this))) {
            final android.content.Context app = getApplicationContext();
            android.widget.Toast.makeText(app, "Restarting Maps to apply", android.widget.Toast.LENGTH_SHORT).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> Shapes.restart(app), 600);
        }
    }

    private void refresh() {
        String h = Shapes.proxyHost(this);
        int p = Shapes.proxyPort(this);
        hostSub.setText(h.isEmpty() ? "None" : h);
        portSub.setText(p > 0 ? String.valueOf(p) : "None");
    }

    private void save() {
        Shapes.setProxyParts(this, enable.isChecked(), Shapes.proxyHost(this), Shapes.proxyPort(this));
        refresh();
    }

    private void ask(String what, String hint, String current, boolean numeric) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint(hint);
        in.setText(current);
        in.setSelection(current.length());
        in.setTextColor(text());
        in.setHintTextColor(summary());
        if (numeric) in.setInputType(InputType.TYPE_CLASS_NUMBER);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(in);
        new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(what)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String v = in.getText().toString().trim();
                    if (numeric) {
                        int p = 0;
                        try { p = v.isEmpty() ? 0 : Integer.parseInt(v); } catch (Throwable ignored) {}
                        Shapes.setProxyParts(this, enable.isChecked(), Shapes.proxyHost(this), p);
                    } else {
                        Shapes.setProxyParts(this, enable.isChecked(), v, Shapes.proxyPort(this));
                    }
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout rowBase(String label, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.setMinimumHeight(dp(60));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(text());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        col.addView(tv);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(summary());
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private View toggleRow(String label, String sub, Switch sw) {
        LinearLayout r = rowBase(label, sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(12));
        r.addView(sw, lp);
        r.setOnClickListener(v -> { if (sw.isEnabled()) sw.toggle(); });
        return r;
    }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}
