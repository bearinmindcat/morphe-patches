package org.ungoogled.honorrecorder;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toolbar;
import java.lang.reflect.Method;

/**
 * The phone's system properties as Honor Recorder reads them through MagicOS's SystemPropertiesEx (its stand-in calls
 * these): Android's own SystemProperties, by reflection. Off MagicOS the MagicOS-only properties (msc.*,
 * ro.build.version.magic, ...) are empty, so the app takes the default it passes in, as on a phone without them.
 */
public final class Magic {
    private static Method get;

    private Magic() {
    }

    /** The phone's own value, or def where it has none. */
    public static String prop(String key, String def) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = key == null ? null : get.invoke(null, key, "");
            return value == null || ((String) value).isEmpty() ? def : (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return def;
        }
    }

    public static int propInt(String key, int def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long propLong(String key, long def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Long.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** As android.os.SystemProperties: y/yes/1/true/on and n/no/0/false/off, else the default. */
    public static boolean propBoolean(String key, boolean def) {
        String value = prop(key, "");
        switch (value) {
            case "1": case "y": case "yes": case "true": case "on":
                return true;
            case "0": case "n": case "no": case "false": case "off":
                return false;
        }
        return def;
    }

    /**
     * Theme.resolveAttribute for Honor Recorder's code (patches/.../honor/MagicFrameworkIds.kt sends its calls here): where
     * the code asked for one of MagicOS's theme attributes (?androidhnext:attr/...), the patch put the app's own
     * resource MagicOS answers with (the same dimension or colour), which no theme has as an attribute; that resource
     * is the answer, as MagicOS's theme would give it.
     */
    public static boolean resolveAttribute(Resources.Theme theme, int id, TypedValue out, boolean resolveRefs) {
        if (theme.resolveAttribute(id, out, resolveRefs)) return true;
        if ((id >>> 24) != 0x7f) return false;
        try {
            theme.getResources().getValue(id, out, resolveRefs);
            out.resourceId = id;
            return true;
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    /**
     * MagicOS's ActionBarEx.setStartIcon / setEndIcon: a cancel button at the toolbar's start and a confirm button at
     * its end, with the ids MagicOS gives them (android:id/icon1 and icon2, which Honor Recorder's click listeners check), the
     * app's own icon or MagicOS's default (the Honor UI kit's cancel / confirm icons the app carries).
     */
    public static void toolbarButton(Toolbar toolbar, boolean start, boolean show, Drawable icon, View.OnClickListener listener) {
        if (toolbar == null) return;
        Context context = toolbar.getContext();
        int id = start ? android.R.id.icon1 : android.R.id.icon2;
        if (start && !show) toolbar.setNavigationIcon(null);
        View old = start ? null : toolbar.findViewById(id);
        if (old != null && old.getParent() == toolbar) toolbar.removeView(old);
        if (!show) return;
        if (icon == null) icon = tinted(context, drawable(context, start ? "icsvg_public_cancel" : "icsvg_public_confirm",
                start ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_save));
        if (start) {
            // the navigation slot, before the title (Toolbar lays START custom views out after it)
            toolbar.setNavigationIcon(icon);
            toolbar.setNavigationContentDescription(android.R.string.cancel);
            toolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    view.setId(id);
                    if (listener != null) listener.onClick(view);
                }
            });
            return;
        }
        ImageButton button = new ImageButton(context, null, android.R.attr.actionButtonStyle);
        button.setId(id);
        button.setImageDrawable(icon);
        button.setContentDescription(context.getText(android.R.string.ok));
        button.setOnClickListener(listener);
        toolbar.addView(button, new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
    }

    private static Drawable drawable(Context context, String name, int fallback) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        try {
            if (id != 0) return context.getDrawable(id);
        } catch (RuntimeException e) {
            // not inflatable here: Android's own
        }
        return context.getDrawable(fallback);
    }

    /**
     * Resources.getIdentifier for Honor Recorder's code (patches/.../honor/MagicFrameworkIds.kt sends its calls here): a
     * lookup of one of MagicOS's framework interpolators by name ("androidhnext" as the package) finds nothing off
     * MagicOS, and loading interpolator 0 throws. It gets the app's own interpolator of that name (the Honor UI kit it
     * bundles carries some), or Android's nearest curve. Every other framework lookup stays 0, as off MagicOS: Honor's
     * libraries look up their framework resources to learn whether they run on MagicOS.
     */
    public static int getIdentifier(Resources resources, String name, String type, String pkg) {
        int id = resources.getIdentifier(name, type, pkg);
        if (id != 0 || name == null) return id;
        String n = name, t = type, p = pkg;
        int colon = n.indexOf(':');
        if (colon >= 0) {
            p = n.substring(0, colon);
            n = n.substring(colon + 1);
        }
        int slash = n.indexOf('/');
        if (slash >= 0) {
            t = n.substring(0, slash);
            n = n.substring(slash + 1);
        }
        if (!"androidhnext".equals(p) || !"interpolator".equals(t)) return 0;
        String app = Application.getProcessName();
        if (app != null && app.indexOf(':') >= 0) app = app.substring(0, app.indexOf(':'));
        id = app == null ? 0 : resources.getIdentifier(n, t, app);
        if (id != 0) return id;
        return n.endsWith("_0_100") ? android.R.interpolator.linear_out_slow_in : android.R.interpolator.fast_out_slow_in;
    }

    /**
     * Whether the phone has MagicOS's framework (its classes on the boot class path, which the app's stand-ins only
     * imitate): for the few places a port swaps a MagicOS-only component for a plain one, so Honor phones keep theirs.
     */
    public static boolean onMagicOs() {
        try {
            Class.forName("com.hihonor.android.os.SystemPropertiesEx", false, null);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Toolbar.setNavigationIcon(int) for Honor Recorder's code: an icon of the Honor UI kit ("icsvg_..."), whose own colour is
     * a light grey placeholder MagicOS's toolbar would tint, gets the theme's control colour, as Android's toolbars
     * colour their navigation icon.
     */
    public static void setNavigationIcon(Toolbar toolbar, int id) {
        toolbar.setNavigationIcon(id);
        if (id == 0) return;
        try {
            if (!toolbar.getResources().getResourceEntryName(id).startsWith("icsvg_")) return;
        } catch (Resources.NotFoundException e) {
            return;
        }
        Drawable icon = toolbar.getNavigationIcon();
        if (icon != null) toolbar.setNavigationIcon(tinted(toolbar.getContext(), icon));
    }

    private static Drawable tinted(Context context, Drawable icon) {
        TypedArray a = context.obtainStyledAttributes(new int[] {android.R.attr.colorControlNormal});
        ColorStateList color = a.getColorStateList(0);
        a.recycle();
        if (color == null) return icon;
        Drawable mutable = icon.mutate();
        mutable.setTintList(color);
        return mutable;
    }
}
