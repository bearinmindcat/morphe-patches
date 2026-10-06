package org.ungoogled.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The frame the You and Timeline screens share, in Maps' own look: a 22 sp Google Sans title
 * with Maps' close icon, then rows of a 16 sp title over a 14 sp summary, optionally led by a
 * 24 dp icon -- in Maps' light, dark or Black theme.
 */
abstract class UiScreen extends Activity {
    /** Maps' own resources, by id: the merged APK strips their names, so getIdentifier cannot find them. */
    static final int CLOSE = 0x7f080538;             // drawable/gs_close_vd_theme_24
    static final int SANS_MEDIUM = 0x7f090000;       // font/GoogleSansMedium
    static final int SANS_REGULAR = 0x7f090001;      // font/GoogleSansRegular
    static final int TEXT_REGULAR = 0x7f090003;      // font/GoogleSansTextRegular
    static final int TEXT_MEDIUM = 0x7f090002;       // font/GoogleSansTextMedium

    private static final int BG = 0xFFFFFFFF, TEXT = 0xFF1F1F1F, SUMMARY = 0xFF444746, ACCENT = 0xFF357989;
    private static final int BG_D = 0xFF131314, TEXT_D = 0xFFE3E3E3, SUMMARY_D = 0xFFC4C7C5, ACCENT_D = 0xFF7FD0DC;
    boolean dark, black;
    LinearLayout body;
    Typeface sans, sansMedium, text, textMedium;

    int bg() { return black ? 0xFF000000 : dark ? BG_D : BG; }
    int text() { return dark ? TEXT_D : TEXT; }
    int summary() { return dark ? SUMMARY_D : SUMMARY; }
    int accent() { return dark ? ACCENT_D : ACCENT; }
    int divider() { return dark ? 0xFF444746 : 0xFFDADCDF; }
    /** The fill of a selected chip and the note field's border. */
    int tonal() { return dark ? 0xFF1F4E56 : 0xFFDAF6FE; }
    int onTonal() { return dark ? 0xFFB3ECF5 : 0xFF174C55; }
    int outline() { return dark ? 0xFF8E918F : 0xFFC7C7C7; }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Shapes.wrap(base));
    }

    /** Builds the window: title bar with close, and an empty scrolling [body]. */
    void frame(String titleText) {
        frame(titleText, true);
    }

    /** As frame(), with the title bar left out when [header] is false (a page drawing its own, as Maps' list pages do). */
    void frame(String titleText, boolean header) {
        black = Shapes.BLACK;
        String darkMode = getSharedPreferences("settings_preference", MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        boolean systemNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = black || "ON".equals(darkMode) || (!"OFF".equals(darkMode) && systemNight);
        sans = font(SANS_REGULAR, Typeface.SANS_SERIF);
        sansMedium = font(SANS_MEDIUM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text = font(TEXT_REGULAR, Typeface.SANS_SERIF);
        textMedium = font(TEXT_MEDIUM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setTitle(titleText);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().getDecorView().setBackgroundColor(bg());
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] sb = Shapes.barInsets(insets, true);
            v.setPadding(sb[0], sb[1], sb[2], sb[3]);
            return insets;
        });

        if (header) {
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(20), dp(16), dp(12), dp(8));
            TextView title = label(titleText, 22, titleColor(), sansMedium);
            bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            View extra = headerAction();
            if (extra != null) bar.addView(extra, new LinearLayout.LayoutParams(dp(48), dp(48)));
            ImageView close = iconButton(drawable(CLOSE, text(), null), "Close");
            close.setOnClickListener(v -> finish());
            bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(bar);
        }

        View top = pinnedTop();
        if (top != null) root.addView(top);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(24));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    int titleColor() { return text(); }

    /** An extra button left of close, or null. */
    View headerAction() { return null; }

    /** Views kept above the scrolling body (a search field, filter chips), or null. */
    View pinnedTop() { return null; }

    TextView section(String label) {
        TextView t = label(label, 14, accent(), textMedium);
        t.setPadding(dp(20), dp(20), dp(20), dp(6));
        return t;
    }

    /** A section title in the You tab's style, with an optional summary and a link on the right ("See all"). */
    View heading(String title, String sub, String link, View.OnClickListener onLink) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(24), dp(8), dp(6));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(title, 18, text(), sansMedium));
        if (sub != null) {
            TextView s = label(sub, 14, summary(), text);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (link != null) {
            TextView l = label(link, 14, accent(), textMedium);
            l.setPadding(dp(12), dp(12), dp(12), dp(12));
            l.setOnClickListener(onLink);
            row.addView(l);
        }
        return row;
    }

    TextView hint(String s) {
        TextView t = label(s, 14, summary(), text);
        t.setPadding(dp(20), dp(4), dp(20), dp(10));
        return t;
    }

    View action(String label, String sub, View.OnClickListener click) {
        LinearLayout r = rowBase(label, sub);
        r.setOnClickListener(click);
        return r;
    }

    LinearLayout rowBase(String label, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.setMinimumHeight(dp(60));
        ripple(r);
        r.addView(label(label, 16, text(), text));
        if (sub != null) {
            TextView s = label(sub, 14, summary(), text);
            s.setPadding(0, dp(2), 0, 0);
            r.addView(s);
        }
        return r;
    }

    /** A row led by a 24 dp icon: the title and summary start at 56 dp, as in Maps' lists. */
    LinearLayout iconRow(Drawable icon, String label, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(16), dp(10));
        r.setMinimumHeight(dp(64));
        ripple(r);
        ImageView i = new ImageView(this);
        i.setImageDrawable(icon);
        r.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = label(label, 16, text(), text);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null) {
            TextView s = label(sub, 14, summary(), text);
            s.setPadding(0, dp(2), 0, 0);
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    /** A horizontal row of filter chips; [on] says which is selected, [pick] gets the tapped index. */
    View chips(String[] names, int on, java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(16), dp(4), dp(16), dp(8));
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            boolean selected = i == on;
            TextView c = label(names[i], 14, selected ? onTonal() : text(), textMedium);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            if (selected) g.setColor(tonal()); else { g.setColor(Color.TRANSPARENT); g.setStroke(Math.max(1, dp(1)), outline()); }
            c.setBackground(g);
            c.setOnClickListener(v -> pick.accept(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.rightMargin = dp(8);
            row.addView(c, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    /** A rounded search box; [changed] gets every edit. */
    EditText searchField(String hintText, java.util.function.Consumer<String> changed) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextColor(text());
        e.setHintTextColor(summary());
        e.setTypeface(text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(28));
        g.setColor(dark ? 0xFF282A2C : 0xFFF0F4F4);
        e.setBackground(g);
        e.setPadding(dp(20), dp(12), dp(20), dp(12));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { changed.accept(s.toString()); }
        });
        return e;
    }

    View rule() {
        View v = new View(this);
        v.setBackgroundColor(divider());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        return v;
    }

    ImageView iconButton(Drawable d, String description) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(d);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(description);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) b.setBackgroundResource(ripple.resourceId);
        return b;
    }

    TextView label(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Maps' drawable [id] tinted [tint]; [fallback] (tinted) when the id does not resolve. */
    Drawable drawable(int id, int tint, Drawable fallback) {
        Drawable d;
        try { d = getDrawable(id); } catch (Throwable t) { d = null; }
        if (d == null) d = fallback != null ? fallback : getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
        d = d.mutate();
        d.setTint(tint);
        return d;
    }

    private void ripple(View v) {
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) v.setBackgroundResource(ripple.resourceId);
    }

    private Typeface font(int id, Typeface fallback) {
        try {
            Typeface f = getResources().getFont(id);
            return f != null ? f : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
}
