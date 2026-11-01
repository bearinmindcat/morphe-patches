package org.ungoogled.honornotes;

import java.lang.reflect.Method;

/**
 * The phone's system properties as Honor Notes reads them through MagicOS's SystemPropertiesEx (its stand-in calls
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
     * Theme.resolveAttribute for Honor Notes's code, which also resolves MagicOS theme attributes by id. Off MagicOS the
     * patch names, in their place, the app's own resource holding what MagicOS's theme gives (replaceMagicFrameworkLiterals);
     * such a resource is answered with its value, as the theme would have.
     */
    public static boolean resolveAttribute(android.content.res.Resources.Theme theme, int id, android.util.TypedValue out,
                                           boolean resolveRefs) {
        if (theme.resolveAttribute(id, out, resolveRefs)) return true;
        if ((id >>> 24) != 0x7f) return false;
        try {
            android.content.res.Resources res = theme.getResources();
            if ("attr".equals(res.getResourceTypeName(id))) return false;
            res.getValue(id, out, true);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Window.setDecorFitsSystemWindows for Honor Notes: since Android 15 an app built for it is laid out edge to edge and
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
     * Resources.getIdentifier for Honor Notes's code, which also looks up MagicOS's themes by name
     * ("androidhnext:style/Theme.Magic", to setTheme or wrap a context in): off MagicOS there is no androidhnext and
     * the answer was 0. Its day/night themes get the app's MagicDayNight styles (followMagicDayNight adds them); every
     * other name is answered as Android answers it.
     */
    public static int getIdentifier(android.content.res.Resources res, String name, String type, String pkg) {
        int id = res.getIdentifier(name, type, pkg);
        if (id != 0 || name == null) return id;
        String full = name.indexOf(':') >= 0 ? name : (pkg == null ? "" : pkg + ":") + (type == null || name.indexOf('/') >= 0 ? "" : type + "/") + name;
        String magic = "androidhnext:style/Theme.Magic";
        if (full.startsWith("androidhnext:dimen/")) {
            // MagicOS's sizes of its own devices' bars (split toolbar, freeform caption, multi-window drag bar): the
            // app's copy, else none -- a zero size (AppCompat's abc_switch_padding, 0px), as the callers read 0.
            String app = appPackage();
            if (app == null) return 0;
            int own = res.getIdentifier(full.substring("androidhnext:dimen/".length()), "dimen", app);
            return own != 0 ? own : res.getIdentifier("abc_switch_padding", "dimen", app);
        }
        if (full.startsWith("androidhnext:id/")) {
            // Views of MagicOS layouts the port stands in with its own (e.g. MagicAppbar's): the app's "magic_" ids.
            String app = appPackage();
            return app == null ? 0 : res.getIdentifier("magic_" + full.substring("androidhnext:id/".length()), "id", app);
        }
        if (!full.startsWith(magic)) return 0;
        String variant = full.substring(magic.length());
        String style = variant.isEmpty() || variant.equals(".WithActionBar") ? "MagicDayNight"
                : variant.equals(".NoActionBar") || variant.equals(".NoTitleBar") ? "MagicDayNight.NoActionBar" : null;
        if (style == null) return 0;
        String app = appPackage();
        return app == null ? 0 : res.getIdentifier(style, "style", app);
    }

    private static String appPackage() {
        try {
            android.content.Context app = (android.content.Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app == null ? null : app.getPackageName();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A theme colour Honor Notes's code reads by attribute id (getTheme().obtainStyledAttributes(new int[]{id})): where
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
