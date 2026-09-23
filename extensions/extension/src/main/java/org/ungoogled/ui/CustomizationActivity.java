package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Customization" screen, reached from the account sheet. Laid out like Maps'
 * own Settings screen (account sheet -> Settings): a 22 sp left-aligned title
 * with Maps' own close icon (gs_close_vd_theme_24) on the right, then plain
 * rows -- 16 sp title, 14 sp summary, 60 dp tall, no icons, no cards -- with
 * sizes measured pixel-for-pixel against that screen. Each toggle is shown
 * only when the patch it controls was included when Maps was patched.
 */
public final class CustomizationActivity extends Activity {
    // Maps' own palette: light = white / grey900 text / grey700 summary; dark = Material 3
    // neutral6 surface (#131314) / neutral90 text; Black theme = pure black surface.
    private static final int BG = 0xFFFFFFFF, TEXT = 0xFF1B1B1F, SUMMARY = 0xFF5F6368;
    private static final int BG_D = 0xFF131314, TEXT_D = 0xFFE3E3E3, SUMMARY_D = 0xFFC4C7C5;
    private boolean dark, black;
    private TextView proxySub;

    private String proxySummary() {
        String effective = Shapes.proxy(this);
        return effective.isEmpty() ? "Off" : effective;
    }

    @Override protected void onResume() {
        super.onResume();
        if (proxySub != null) proxySub.setText(proxySummary());
    }

    private int bg() { return black ? 0xFF000000 : dark ? BG_D : BG; }
    private int text() { return dark ? TEXT_D : TEXT; }
    private int summary() { return dark ? SUMMARY_D : SUMMARY; }

    /** Same override configuration every Maps Activity gets from the patched base class. */
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Shapes.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Follow Maps' theme, not the system's: Maps decides dark/light from its own
        // settings_preference/dark_mode (ON | OFF | FOLLOW_SYSTEM), see Shapes.enforceDark.
        black = Shapes.BLACK;
        String darkMode = getSharedPreferences("settings_preference", MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        boolean systemNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = black || "ON".equals(darkMode) || (!"OFF".equals(darkMode) && systemNight);
        setTitle("Customization");
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

        // header, as on Settings: 22 sp left-aligned title, Maps' close icon on the right
        android.widget.FrameLayout header = new android.widget.FrameLayout(this);
        header.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText("Customization");
        title.setTextColor(text());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        header.addView(title, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        ImageView close = new ImageView(this);
        int closeRes = getResources().getIdentifier("gs_close_vd_theme_24", "drawable", getPackageName());
        close.setImageResource(closeRes != 0 ? closeRes : android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(text());
        close.setContentDescription("Close");
        close.setOnClickListener(v -> finish());
        header.addView(close, new android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(24));

        if (Shapes.rectShapesPatched()) {
            Switch rect = new Switch(this);
            rect.setChecked(Shapes.enabled(this));
            body.addView(toggleRow("Rectangle Shapes", "Changes circle & oval icons to squares & rectangles", rect));
            rect.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setEnabled(this, on);
                restartSoon(b);
            });
        }

        if (Shapes.blackThemePatched()) {
            Switch black = new Switch(this);
            black.setChecked(Shapes.blackEnabled(this));
            body.addView(toggleRow("Black theme", "Also disables light & dark theme", black));
            black.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setBlackEnabled(this, on);
                restartSoon(b);
            });
        }

        if (Shapes.navZoomPatched()) {
            Switch navz = new Switch(this);
            navz.setChecked(Shapes.navZoomEnabled(this));
            body.addView(toggleRow("Zoom controls in navigation", null, navz));
            navz.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setNavZoomEnabled(this, on);
                // no restart: the navigation ticker adds and removes the tiles live
            });
        }

        if (Shapes.hideAdsPatched()) {
            Switch ads = new Switch(this);
            ads.setChecked(Shapes.hideAdsEnabled(this));
            body.addView(toggleRow("Hide sponsored content", "Removes promoted pins & sponsored search results", ads));
            ads.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setHideAdsEnabled(this, on);
                restartSoon(b);
            });
        }

        if (Shapes.hideExplorePatched()) {
            Switch explore = new Switch(this);
            explore.setChecked(Shapes.hideExploreEnabled(this));
            body.addView(toggleRow("Hide explore sheet", null, explore));
            explore.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setHideExploreEnabled(this, on);
                restartSoon(b);
            });
        }

        if (Shapes.hideTabsPatched()) {
            Switch tabs = new Switch(this);
            tabs.setChecked(Shapes.hideTabsEnabled(this));
            body.addView(toggleRow("Hide bottom tabs", null, tabs));
            tabs.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Shapes.setHideTabsEnabled(this, on);
                restartSoon(b);
            });
        }

        if (Shapes.betterOfflinePatched()) {
            Switch offline = new Switch(this);
            offline.setChecked(Shapes.betterOfflineEnabled(this));
            body.addView(toggleRow("Better offline maps", null, offline));
            // read live by the picker, so no restart
            offline.setOnCheckedChangeListener((CompoundButton b, boolean on) -> Shapes.setBetterOfflineEnabled(this, on));
        }

        if (Shapes.locationSourcePatched()) {
            // OsmAnd-style single choice. Google Play Services is always listed, but greyed
            // out and unselectable while Play services is missing or disabled.
            final boolean playAvailable = Shapes.playServicesUsable(this);
            final boolean usePlay = playAvailable && Shapes.playLocationEnabled(this);
            View locRow = row("Location source", usePlay ? "Google Play Services" : "Android API");
            locRow.setOnClickListener(v -> {
                final String[] options = {"Android API", "Google Play Services"};
                ChoiceAdapter adapter = new ChoiceAdapter(this, options, playAvailable, text(), dark ? 0xFF6B6B6B : 0xFFB0B0B0);
                new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle("Location source")
                        .setSingleChoiceItems(adapter, usePlay ? 1 : 0, (d, which) -> {
                            d.dismiss();
                            boolean play = which == 1;
                            if (play == usePlay) return;
                            Shapes.setPlayLocationEnabled(this, play);
                            restartSoon(v);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            body.addView(locRow);
        }

        if (Shapes.proxyPatched()) {
            // its own screen (Enable / Host / Port), like OsmAnd's
            LinearLayout proxyRow = rowBase("Proxy", proxySummary());
            proxySub = (TextView) ((LinearLayout) proxyRow.getChildAt(0)).getChildAt(1);
            proxyRow.setOnClickListener(v -> startActivity(new android.content.Intent(this, ProxyActivity.class)));
            body.addView(proxyRow);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** Single-choice list whose second entry (Google Play Services) is greyed out and unselectable
     *  while Play services is unavailable. */
    private static final class ChoiceAdapter extends android.widget.ArrayAdapter<String> {
        private final boolean playAvailable; private final int on, off;
        ChoiceAdapter(android.content.Context c, String[] items, boolean playAvailable, int on, int off) {
            super(c, android.R.layout.simple_list_item_single_choice, items);
            this.playAvailable = playAvailable; this.on = on; this.off = off;
        }
        @Override public boolean isEnabled(int position) { return position == 0 || playAvailable; }
        @Override public boolean areAllItemsEnabled() { return playAvailable; }
        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            boolean enabled = isEnabled(position);
            row.setEnabled(enabled);
            if (row instanceof TextView) ((TextView) row).setTextColor(enabled ? on : off);
            return row;
        }
    }

    private void restartSoon(View v) {
        Toast.makeText(this, "Restarting Maps to apply", Toast.LENGTH_SHORT).show();
        v.postDelayed(() -> Shapes.restart(this), 600);
    }

    /** [title 16 sp / summary 14 sp] -- the Settings row, text only. */
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

    private View row(String label, String sub) { return rowBase(label, sub); }

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
