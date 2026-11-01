package org.ungoogled.honornotes;

import android.app.ActionBar;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toolbar;

/**
 * MagicOS's toolbar start and end icons (ActionBarEx.setStartIcon / setEndIcon) for Honor Notes, which its stand-ins call:
 * the "cancel" (X) and "done" (check) buttons of edit screens. MagicOS's HwToolbar draws them itself, with the view ids
 * android:id/icon1 (start) and android:id/icon2 (end) that the app looks up and tells clicks apart by. Off MagicOS the
 * start icon is the toolbar's navigation button and the end icon a button at the toolbar's end, with those ids; without
 * a toolbar, the action bar gets a custom view holding both around the title.
 */
public final class MagicToolbar {
    /** The start icon each toolbar shows as its navigation icon. */
    private static final java.util.Map<Toolbar, Drawable> STARTS = new java.util.WeakHashMap<>();

    private MagicToolbar() {
    }

    public static void setStartIcon(ActionBar bar, Toolbar toolbar, boolean visible, Drawable icon, View.OnClickListener listener) {
        if (toolbar == null) {
            setIcon(bar, true, visible, icon, listener);
            return;
        }
        if (!visible) {
            // MagicOS's start icon is its own view beside the navigation button: hiding it leaves a navigation icon
            // the app set itself (a back arrow) alone.
            if (toolbar.getNavigationIcon() != null && toolbar.getNavigationIcon() == STARTS.get(toolbar)) {
                toolbar.setNavigationIcon(null);
                toolbar.setNavigationOnClickListener(null);
            }
            STARTS.remove(toolbar);
            return;
        }
        Drawable d = icon != null ? icon : glyph(toolbar.getContext(), false);
        toolbar.setNavigationIcon(d);
        STARTS.put(toolbar, d);
        toolbar.setNavigationOnClickListener(listener);
        if (toolbar.getNavigationContentDescription() == null) {
            toolbar.setNavigationContentDescription(android.R.string.cancel);
        }
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof ImageButton && ((ImageButton) child).getDrawable() == d) child.setId(android.R.id.icon1);
        }
    }

    public static void setEndIcon(ActionBar bar, Toolbar toolbar, boolean visible, Drawable icon, View.OnClickListener listener) {
        if (toolbar == null) {
            setIcon(bar, false, visible, icon, listener);
            return;
        }
        ImageButton end = toolbar.findViewById(android.R.id.icon2);
        if (end == null) {
            end = button(toolbar.getContext(), android.R.id.icon2);
            Toolbar.LayoutParams lp = new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL);
            toolbar.addView(end, lp);
        }
        end.setImageDrawable(icon != null ? icon : glyph(toolbar.getContext(), true));
        end.setOnClickListener(listener);
        end.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static void setEndContentDescription(Toolbar toolbar, CharSequence description) {
        View end = toolbar == null ? null : toolbar.findViewById(android.R.id.icon2);
        if (end != null) end.setContentDescription(description);
    }

    public static void setStartContentDescription(Toolbar toolbar, CharSequence description) {
        if (toolbar != null && toolbar.getNavigationIcon() != null) toolbar.setNavigationContentDescription(description);
    }

    /** MagicOS's "done" icon enabled or not (edit screens enable it once there is something to save). */
    public static void setEditPositiveEnabled(Toolbar toolbar, boolean enabled) {
        View end = toolbar == null ? null : toolbar.findViewById(android.R.id.icon2);
        if (end != null) end.setEnabled(enabled);
    }

    /** MagicOS's toolbar effects (blurred drop-down, split toolbar) have no counterpart: nothing to do. */
    public static void setDropdownBlurEnabled(Toolbar toolbar, boolean enabled) {
    }

    public static void setSplitBackgroundDrawable(Toolbar toolbar, Drawable drawable) {
    }

    public static void setSplitToolbarForce(Toolbar toolbar, boolean force) {
    }

    public static void setStartContentDescription(ActionBar bar, CharSequence description) {
        View start = bar == null || bar.getCustomView() == null ? null : bar.getCustomView().findViewById(android.R.id.icon1);
        if (start != null) start.setContentDescription(description);
    }

    public static void setEndContentDescription(ActionBar bar, CharSequence description) {
        View end = bar == null || bar.getCustomView() == null ? null : bar.getCustomView().findViewById(android.R.id.icon2);
        if (end != null) end.setContentDescription(description);
    }

    /** MagicOS's custom title between the icons. */
    public static void setCustomTitle(ActionBar bar, View title) {
        if (bar == null) return;
        LinearLayout row = row(bar);
        View old = row.findViewWithTag(MagicToolbar.class);
        if (old != null) row.removeView(old);
        if (title == null) return;
        if (title.getParent() instanceof ViewGroup) ((ViewGroup) title.getParent()).removeView(title);
        title.setTag(MagicToolbar.class);
        row.addView(title, Math.min(1, row.getChildCount()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private static void setIcon(ActionBar bar, boolean start, boolean visible, Drawable icon, View.OnClickListener listener) {
        if (bar == null) return;
        LinearLayout row = row(bar);
        int id = start ? android.R.id.icon1 : android.R.id.icon2;
        ImageButton button = row.findViewById(id);
        if (button == null) {
            button = button(row.getContext(), id);
            row.addView(button, start ? 0 : row.getChildCount());
        }
        button.setImageDrawable(icon != null ? icon : glyph(row.getContext(), !start));
        button.setOnClickListener(listener);
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** The action bar's custom view holding the icons and a custom title. */
    private static LinearLayout row(ActionBar bar) {
        View custom = bar.getCustomView();
        if (custom instanceof LinearLayout && custom.getTag() == MagicToolbar.class) return (LinearLayout) custom;
        LinearLayout row = new LinearLayout(bar.getThemedContext());
        row.setTag(MagicToolbar.class);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (custom != null) {
            if (custom.getParent() instanceof ViewGroup) ((ViewGroup) custom.getParent()).removeView(custom);
            custom.setTag(MagicToolbar.class);
            row.addView(custom, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        bar.setCustomView(row, new ActionBar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.setDisplayShowCustomEnabled(true);
        bar.setDisplayShowTitleEnabled(false);
        return row;
    }

    private static ImageButton button(Context context, int id) {
        ImageButton b = new ImageButton(context);
        b.setId(id);
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        b.setBackground(a.getDrawable(0));
        a.recycle();
        int pad = Math.round(12 * context.getResources().getDisplayMetrics().density);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(context.getString(id == android.R.id.icon2 ? android.R.string.ok : android.R.string.cancel));
        return b;
    }

    /** MagicOS's default icons: a cross (cancel) or a check mark (done), 24dp, in the toolbar's icon colour. */
    private static Drawable glyph(Context context, boolean check) {
        Path path = new Path();
        if (check) {
            path.moveTo(4.5f, 12.5f);
            path.lineTo(9.5f, 17.5f);
            path.lineTo(19.5f, 7f);
        } else {
            path.moveTo(6f, 6f);
            path.lineTo(18f, 18f);
            path.moveTo(18f, 6f);
            path.lineTo(6f, 18f);
        }
        ShapeDrawable d = new ShapeDrawable(new PathShape(path, 24f, 24f));
        int size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        d.setIntrinsicWidth(size);
        d.setIntrinsicHeight(size);
        Paint paint = d.getPaint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.colorControlNormal});
        android.content.res.ColorStateList tint = a.getColorStateList(0);
        a.recycle();
        if (tint != null) d.setTintList(tint);
        else paint.setColor(0xff000000);
        return d;
    }
}
