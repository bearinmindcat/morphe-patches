package org.ungoogled.APP;

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
 * The phone's system properties as APP_NAME reads them through MagicOS's SystemPropertiesEx (its stand-in calls
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
     * Theme.resolveAttribute for APP_NAME's code (patches/.../honor/MagicFrameworkIds.kt sends its calls here): where
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
     * its end, with the ids MagicOS gives them (android:id/icon1 and icon2, which APP_NAME's click listeners check), the
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
     * Resources.getIdentifier for APP_NAME's code (patches/.../honor/MagicFrameworkIds.kt sends its calls here): a
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
     * Toolbar.setNavigationIcon(int) for APP_NAME's code: an icon of the Honor UI kit ("icsvg_..."), whose own colour is
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
     * Window.setDecorFitsSystemWindows for APP_NAME: since Android 15 an app built for it is laid out edge to edge and
     * "fits" is ignored, so content the app keeps out of the status and navigation bars this way went under them.
     * Pads the window's content by as much of the bars (and the keyboard, for adjustResize) as it overlaps, after each
     * layout and insets change, as fitting did.
     */
    public static void setDecorFitsSystemWindows(android.view.Window window, boolean fits) {
        window.setDecorFitsSystemWindows(fits);
        if (android.os.Build.VERSION.SDK_INT < 35) return;
        android.view.View decor = window.getDecorView();
        android.view.View content = decor.findViewById(android.R.id.content);
        if (content == null) return;
        android.view.View.OnLayoutChangeListener old = FITTED.remove(content);
        if (old != null) {
            content.removeOnLayoutChangeListener(old);
            decor.setOnApplyWindowInsetsListener(null);
            content.setPadding(0, 0, 0, 0);
        }
        if (!fits) return;
        android.view.View.OnLayoutChangeListener fit = (v, l, t, r, b, ol, ot, or, ob) -> fitContent(window, v);
        FITTED.put(content, fit);
        content.addOnLayoutChangeListener(fit);
        decor.setOnApplyWindowInsetsListener((v, insets) -> {
            android.view.WindowInsets result = v.onApplyWindowInsets(insets);
            content.post(() -> fitContent(window, content));
            return result;
        });
        decor.requestApplyInsets();
    }

    private static final java.util.Map<android.view.View, android.view.View.OnLayoutChangeListener> FITTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void fitContent(android.view.Window window, android.view.View content) {
        android.view.WindowInsets insets = content.getRootWindowInsets();
        android.view.View decor = window.getDecorView();
        if (insets == null || decor.getWidth() == 0) return;
        // A translucent window (a sheet over the previous screen) is meant to cover the bars.
        android.content.res.TypedArray style = window.getContext().obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
        boolean translucent = style.getBoolean(0, false);
        style.recycle();
        if (translucent) {
            if (content.getPaddingTop() != 0 || content.getPaddingBottom() != 0) content.setPadding(0, 0, 0, 0);
            return;
        }
        // A window with a showing action bar has the bars kept clear by the action bar's own layout (as before).
        if (window.getCallback() instanceof android.app.Activity) {
            android.app.ActionBar bar = ((android.app.Activity) window.getCallback()).getActionBar();
            if (bar != null && bar.isShowing()) {
                if (content.getPaddingTop() != 0 || content.getPaddingBottom() != 0) content.setPadding(0, 0, 0, 0);
                return;
            }
        }
        int types = android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout();
        int adjust = window.getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        if (adjust == android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) types |= android.view.WindowInsets.Type.ime();
        android.graphics.Insets bars = insets.getInsets(types);
        int[] at = new int[2];
        content.getLocationInWindow(at);
        int left = Math.max(0, bars.left - at[0]);
        int top = Math.max(0, bars.top - at[1]);
        int right = Math.max(0, bars.right - (decor.getWidth() - at[0] - content.getWidth()));
        int bottom = Math.max(0, bars.bottom - (decor.getHeight() - at[1] - content.getHeight()));
        if (content.getPaddingLeft() != left || content.getPaddingTop() != top || content.getPaddingRight() != right
                || content.getPaddingBottom() != bottom) {
            content.setPadding(left, top, right, bottom);
        }
    }

    /**
     * A theme colour APP_NAME's code reads by attribute id (getTheme().obtainStyledAttributes(new int[]{id})): where
     * the patch put the app's own colour resource in place of a MagicOS attribute, that colour.
     */
    public static int themeColor(android.content.Context context, int id) {
        if (context == null) return 0;
        try {
            if ((id >>> 24) == 0x7f && !"attr".equals(context.getResources().getResourceTypeName(id))) {
                return context.getColor(id);
            }
        } catch (RuntimeException e) {
            return 0;
        }
        android.content.res.TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{id});
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }
}
