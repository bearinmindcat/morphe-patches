package org.ungoogled.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The "Place saved" sheet, a copy of Maps' own, measured off a signed-in Maps on
 * the phone: full height, stopping 8 dp under the status bar; the title with a
 * bookmark-check badge and a round close button; a note; the lists -- Want to go,
 * Travel plans, Starred places, Favorites and the user's own, each "Private · N
 * places" with a checkbox, divided edge to edge -- and Unsave / Done pinned to the
 * bottom, all set in Maps' bundled Google Sans. Maps' "Customize or add to list"
 * line is left out at the user's request. Maps' sheet itself cannot be reused: it
 * is a front end for the account's lists on Google's servers. Its pin-style row is
 * left out for the same reason (the pins are drawn from the account).
 */
final class SaveSheet {
    /** Maps' own resources, by id: the merged APK strips their names, so getIdentifier cannot find them. */
    private static final int SUITCASE = 0x7f080879;          // drawable/ic_suitcase (density split)
    private static final int CLOSE = 0x7f080538;             // drawable/gs_close_vd_theme_24
    private static final int BOOKMARK_CHECK = 0x7f080514;    // drawable/gs_bookmark_check_fill1_vd_theme_24
    private static final int SANS_MEDIUM = 0x7f090000;       // font/GoogleSansMedium
    private static final int TEXT = 0x7f090003;              // font/GoogleSansTextRegular
    private static final int TEXT_MEDIUM = 0x7f090002;       // font/GoogleSansTextMedium

    private final Activity a;
    private final SavedStore.Place place;
    private final Runnable after;
    private final boolean dark;
    private final Dialog dialog;
    private final LinearLayout rows;
    private final EditText note;
    private LinearLayout sheetView;
    private boolean unsaved;
    private final Typeface sansMedium, text, textMedium;

    // Maps' sheet, light theme, as sampled off the screen; the dark ones are their dark-theme counterparts.
    private final int bg, title, name, summary, typed, divider, handle, outline, checkbox, primary, onPrimary,
            tonal, onTonal, badge, onBadge, closeFill, onClose;

    private SaveSheet(Activity a, SavedStore.Place place, Runnable after) {
        this.a = a;
        this.place = place;
        this.after = after;
        String darkMode = a.getSharedPreferences("settings_preference", Context.MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        boolean night = (a.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = Shapes.BLACK || "ON".equals(darkMode) || (!"OFF".equals(darkMode) && night);
        bg = Shapes.BLACK ? 0xFF000000 : dark ? 0xFF1E1F20 : 0xFFFFFFFF;
        title = dark ? 0xFFE3E3E3 : 0xFF535353;
        name = dark ? 0xFFE3E3E3 : 0xFF515255;
        summary = dark ? 0xFFC4C7C5 : 0xFF717579;
        typed = dark ? 0xFFE3E3E3 : 0xFF1F1F1F;
        divider = dark ? 0xFF444746 : 0xFFDADCDF;
        handle = dark ? 0xFF5F6368 : 0xFFC7C7C7;
        outline = dark ? 0xFF8E918F : 0xFFC7C7C7;
        checkbox = dark ? 0xFFC4C7C5 : 0xFF606367;
        primary = dark ? 0xFF7FD0DC : 0xFF357989;
        onPrimary = dark ? 0xFF00363D : 0xFFFFFFFF;
        tonal = dark ? 0xFF1F4E56 : 0xFFDAF6FE;
        onTonal = dark ? 0xFFB3ECF5 : 0xFF356974;
        badge = dark ? 0xFF1F4E56 : 0xFFB4DCE7;
        onBadge = dark ? 0xFFB3ECF5 : 0xFF081F24;
        closeFill = dark ? 0xFF303134 : 0xFFF2F2F2;
        onClose = dark ? 0xFFE3E3E3 : 0xFF1F1F1F;
        sansMedium = font(SANS_MEDIUM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text = font(TEXT, Typeface.SANS_SERIF);
        textMedium = font(TEXT_MEDIUM, Typeface.create("sans-serif-medium", Typeface.NORMAL));

        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        note = new EditText(a);
        build();
    }

    /** Save: keeps the place at once, like Maps' quick save, then shows the sheet. */
    static void show(Activity a, SavedStore.Place candidate, Runnable after) {
        SavedStore.load(a);
        SavedStore.Place p = SavedStore.keep(a, candidate);
        new SaveSheet(a, p, after).dialog.show();
    }

    private void build() {
        LinearLayout sheet = new LinearLayout(a);
        sheetView = sheet;
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        float r = dp(28);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shape);
        sheet.setElevation(dp(6));
        sheet.setClickable(true);   // taps on the sheet itself do not close it

        // drag handle, 40 x 4 dp, 8 dp from the top
        View grip = new View(a);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(handle);
        pill.setCornerRadius(dp(2));
        grip.setBackground(pill);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(40), dp(4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.topMargin = dp(8);
        sheet.addView(grip, gl);

        // "Place saved", then a 32 dp bookmark-check badge and a 32 dp round close button, 24 dp apart
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.gravity = Gravity.TOP;
        tl.topMargin = dp(4);
        head.addView(label("Place saved", 22, title, sansMedium), tl);
        head.addView(roundIcon(drawable(BOOKMARK_CHECK), new PathIcon(PathIcon.BOOKMARK, onBadge), 20, badge, onBadge, "Saved"),
                new LinearLayout.LayoutParams(dp(32), dp(32)));
        View close = roundIcon(drawable(CLOSE), a.getDrawable(android.R.drawable.ic_menu_close_clear_cancel), 24,
                closeFill, onClose, "Close");
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(32), dp(32));
        cl.leftMargin = dp(24);
        head.addView(close, cl);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, dp(32));
        hl.topMargin = dp(8);
        sheet.addView(head, hl);

        // the scrolling middle: the note, "Add to list" and the lists
        LinearLayout middle = new LinearLayout(a);
        middle.setOrientation(LinearLayout.VERTICAL);

        note.setHint("Add a note?");
        note.setText(place.note);
        note.setTextColor(typed);
        note.setHintTextColor(summary);
        note.setTypeface(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.TRANSPARENT);
        box.setStroke(dp(1), outline);
        box.setCornerRadius(dp(8));
        note.setBackground(box);
        note.setPadding(dp(16), dp(7), dp(16), dp(8));
        note.setMinHeight(dp(56));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.setMargins(dp(20), dp(20), dp(20), 0);
        middle.addView(note, nl);

        LinearLayout addRow = new LinearLayout(a);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(20), 0, dp(20), 0);
        addRow.addView(label("Add to list", 16, title, sansMedium), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView newList = label("New list", 14, primary, textMedium);
        Drawable plus = new PathIcon(PathIcon.ADD, primary);
        plus.setBounds(0, 0, dp(18), dp(18));
        newList.setCompoundDrawables(plus, null, null, null);
        newList.setCompoundDrawablePadding(dp(8));
        newList.setGravity(Gravity.CENTER_VERTICAL);
        newList.setPadding(dp(8), dp(1), 0, 0);
        newList.setOnClickListener(v -> SavedPlaces.newList(a, n -> {
            String id = SavedStore.addList(a, n);
            Set<String> in = new LinkedHashSet<>(place.lists);
            in.add(id);
            SavedStore.setLists(a, place, in);
            renderRows();
        }));
        addRow.addView(newList, new LinearLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, dp(48));
        al.topMargin = dp(7);
        middle.addView(addRow, al);
        middle.addView(rows);
        renderRows();

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(middle);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Unsave | Done, pinned to the bottom like Maps', 8 dp above the navigation bar
        LinearLayout buttons = new LinearLayout(a);
        buttons.setPadding(dp(20), dp(12), dp(20), dp(8));
        TextView unsave = button("Unsave", tonal, onTonal);
        unsave.setOnClickListener(v -> {
            unsaved = true;
            SavedStore.unsave(a, place);
            Toast.makeText(a, "Removed from saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        TextView done = button("Done", primary, onPrimary);
        done.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(unsave, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(40), 1f);
        dl.leftMargin = dp(8);
        buttons.addView(done, dl);
        sheet.addView(buttons);

        dialog.setOnDismissListener(d -> {
            if (!unsaved) SavedStore.setNote(a, place, note.getText().toString());
            SavedPlaces.refreshButtons();
            if (after != null) after.run();
        });

        // Pulled down (from its top, or from the list when it is scrolled to the top): it follows
        // the finger and closes past a quarter of its height or on a fling, like Maps' sheets.
        // A tap on the strip above it closes it too.
        FrameLayout root = new DragFrame(a, sheet, scroll, this::slideAway, dialog::dismiss);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM);
        root.addView(sheet, sl);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] bars = Shapes.barInsets(insets, true);
            int top = bars[1], bottom = bars[3];
            // Maps' sheet stops 8 dp under the status bar and runs down behind the navigation bar.
            if (sl.topMargin != top + dp(8)) {
                sl.topMargin = top + dp(8);
                sheet.setLayoutParams(sl);
            }
            sheet.setPadding(0, 0, 0, bottom);
            return insets;
        });
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Reach behind the system bars and paint the navigation bar with the sheet: otherwise,
            // with three-button navigation, Maps' own content (the place's photos) shows through it.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(dark ? 0 : light, light);
                    // the status bar shows Maps under the dim: keep its icons as Maps has them
                    android.view.WindowInsetsController mine = a.getWindow().getInsetsController();
                    int status = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    if (mine != null) c.setSystemBarsAppearance(mine.getSystemBarsAppearance() & status, status);
                }
            }
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setStatusBarColor(Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);   // slides up from the bottom, as Maps' does
            w.setDimAmount(0.32f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void slideAway() {
        sheetView.animate().translationY(sheetView.getHeight()).setDuration(160).withEndAction(dialog::dismiss).start();
    }

    /**
     * Lets the sheet be pulled down. It takes over a gesture only once it is a downward drag
     * that started above the list, or on the list while the list is at its top, so taps,
     * checkboxes, the note field and list scrolling behave as usual.
     */
    static final class DragFrame extends FrameLayout {
        private final View sheet;
        private final ScrollView list;
        private final Runnable away, outside;
        private final int slop;
        private float downX, downY;
        private boolean dragging;
        private android.view.VelocityTracker velocity;

        DragFrame(Context c, View sheet, ScrollView list, Runnable away, Runnable outside) {
            super(c);
            this.sheet = sheet;
            this.list = list;
            this.away = away;
            this.outside = outside;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private boolean onList(float rawY) {
            int[] at = new int[2];
            list.getLocationOnScreen(at);
            return rawY >= at[1] && rawY <= at[1] + list.getHeight();
        }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    track(e, true);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    track(e, false);
                    float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                    if (dy > slop && dy > Math.abs(dx) && (!onList(downY) || list.getScrollY() == 0)) {
                        dragging = true;
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    track(e, true);
                    return true;   // nothing below took it: follow it in case it becomes a drag
                case android.view.MotionEvent.ACTION_MOVE: {
                    track(e, false);
                    float dy = e.getRawY() - downY;
                    if (!dragging && dy > slop) dragging = true;
                    if (dragging) sheet.setTranslationY(Math.max(0, dy));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    track(e, false);
                    float vy = 0;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        vy = velocity.getYVelocity();
                        velocity.recycle();
                        velocity = null;
                    }
                    if (dragging) {
                        float fling = 1200 * getResources().getDisplayMetrics().density;
                        if (sheet.getTranslationY() > sheet.getHeight() / 4f || vy > fling) away.run();
                        else sheet.animate().translationY(0).setDuration(160).start();
                    } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        int[] at = new int[2];
                        sheet.getLocationOnScreen(at);
                        if (downY < at[1]) outside.run();   // a tap on the dimmed strip above the sheet
                    }
                    dragging = false;
                    return true;
                }
                default:
                    return true;
            }
        }

        private void track(android.view.MotionEvent e, boolean reset) {
            if (reset || velocity == null) {
                if (velocity != null) velocity.recycle();
                velocity = android.view.VelocityTracker.obtain();
            }
            // Raw coordinates: the sheet moves under the finger while it is dragged.
            android.view.MotionEvent raw = android.view.MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            velocity.addMovement(raw);
            raw.recycle();
        }
    }

    /**
     * One 66 dp row per list, divided edge to edge: a 24 dp icon at 20 dp, the name over
     * "Private · N places" at 56 dp, a checkbox; ticking applies at once.
     */
    private void renderRows() {
        rows.removeAllViews();
        for (Map.Entry<String, String> e : SavedStore.lists.entrySet()) {
            final String id = e.getKey();
            final boolean in = place.lists.contains(id);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), 0, dp(16), 0);
            ImageView icon = new ImageView(a);
            icon.setImageDrawable(listIcon(id));
            row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), dp(1), dp(8), 0);
            TextView listName = label(e.getValue(), 16, name, text);
            listName.setSingleLine(true);
            listName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(listName);
            int n = SavedStore.countIn(id);
            TextView count = label("Private · " + n + (n == 1 ? " place" : " places"), 12, summary, text);
            LinearLayout.LayoutParams cnt = new LinearLayout.LayoutParams(-2, -2);
            cnt.topMargin = dp(4);
            col.addView(count, cnt);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView box = new ImageView(a);
            box.setImageDrawable(new PathIcon(in ? PathIcon.CHECK_ON : PathIcon.CHECK_OFF, in ? primary : checkbox));
            box.setPadding(dp(12), dp(12), dp(12), dp(12));
            box.setContentDescription(e.getValue());
            row.addView(box, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.setOnClickListener(v -> {
                Set<String> lists = new LinkedHashSet<>(place.lists);
                if (in) lists.remove(id); else lists.add(id);
                SavedStore.setLists(a, place, lists);
                renderRows();
            });
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(in);
                }
            });
            row.setMinimumHeight(dp(66));
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View line = new View(a);
            line.setBackgroundColor(divider);
            rows.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private Drawable listIcon(String id) {
        return listIcon(a, id, dark);
    }

    /** A list's icon in Maps' colours: flag, suitcase, star, heart, or a plain list for the user's own. */
    static Drawable listIcon(Context c, String id, boolean dark) {
        switch (id) {
            case SavedStore.WANT_TO_GO: return new PathIcon(PathIcon.FLAG, dark ? 0xFF81C995 : 0xFF458C47);
            case SavedStore.TRAVEL: {
                Drawable d;
                try { d = c.getDrawable(SUITCASE); } catch (Throwable t) { d = null; }
                int tint = dark ? 0xFF78D9EC : 0xFF357989;
                if (d == null) return new PathIcon(PathIcon.WORK, tint);
                d = d.mutate();
                d.setTint(tint);
                return d;
            }
            case SavedStore.STARRED: return new PathIcon(PathIcon.STAR, dark ? 0xFFFCAD70 : 0xFFD57A2D);
            case SavedStore.FAVOURITES: return new PathIcon(PathIcon.HEART, dark ? 0xFFFF8BCB : 0xFFD23B8F);
            default: return new PathIcon(PathIcon.LIST, dark ? 0xFF7CD0E0 : 0xFF489CAC);
        }
    }

    /** A filled circle with a [size] dp icon in it: the saved badge and the close button. */
    private View roundIcon(Drawable icon, Drawable fallback, int size, int fill, int ink, String description) {
        ImageView v = new ImageView(a);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(fill);
        v.setBackground(circle);
        Drawable d = (icon != null ? icon : fallback).mutate();
        d.setTint(ink);
        v.setImageDrawable(d);
        int pad = dp(32 - size) / 2;
        v.setPadding(pad, pad, pad, pad);
        v.setContentDescription(description);
        return v;
    }

    private TextView button(String s, int fill, int ink) {
        TextView b = label(s, 14, ink, textMedium);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(20));
        b.setBackground(g);
        return b;
    }

    private TextView label(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private Drawable drawable(int id) {
        try { return a.getDrawable(id); } catch (Throwable t) { return null; }
    }

    /** One of Maps' bundled Google Sans faces, or [fallback] if it cannot be loaded. */
    private Typeface font(int id, Typeface fallback) {
        try {
            Typeface f = a.getResources().getFont(id);
            return f != null ? f : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Rounded, as Compose rounds Maps' own sizes: a 1 dp divider is 3 px at 2.7x, not 2. */
    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()));
    }
}
