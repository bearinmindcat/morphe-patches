package org.ungoogled.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

/**
 * Runtime half of the Ungoogled Maps UI patches, merged into Maps as a patch
 * extension. Ported from the ungoogled-maps build tooling's classes11.dex.
 *
 * "Rectangle shapes" uses two mechanisms, both keyed off one preference:
 *
 * 1. Resources. The patch ships a sharp-cornered copy of every corner-bearing
 *    XML resource under the -mnc9999 qualifier. When the toggle is on, every
 *    Activity's base Context is replaced with one whose override Configuration
 *    sets mnc=9999 (the patch hooks the app's common Activity base class), so
 *    XML shapes and styles resolve to the sharp variants. Nothing else in the
 *    app reads mnc. 9999 because Morphe reserves mobile codes 1000..9999 for
 *    configurations that patches add.
 *
 * 2. Code. Most of Maps' UI is built by its own Java view DSL, which computes
 *    corner radii from dp literals in code and hands them straight to the
 *    framework (GradientDrawable.setCornerRadius, Path.addRoundRect,
 *    Canvas.drawRoundRect, Outline.setRoundRect, OvalShape, ...). Resources
 *    never see those, so the patch rewrites every such call site into the
 *    static shims below (same registers, invoke-virtual -> invoke-static with
 *    the receiver as first argument). They clamp the radius to RADIUS_DP --
 *    and turn circles and ovals into small-radius rectangles -- while the
 *    toggle is on, and pass through untouched otherwise. The shims have no
 *    Context, so the toggle state is cached in RECT by wrap(), which runs
 *    before any Activity inflates anything.
 *
 * "Black theme" rides the same two rails: mcc=9999 (independent of mnc)
 * selects the -mcc9999[-night] colour variants, and color() -- reached from
 * Maps' literal-colour leaves and from the setColor-family shims below --
 * maps dark neutral greys to black.
 *
 * Every feature flag is also gated on a "patched" marker: a method returning
 * false that the corresponding patch rewrites to return true. Only included
 * patches can ever turn on (the shared shims serve both Rectangle shapes and
 * Black theme, so a build with one must never behave like the other), and
 * the Customization screen shows a toggle only for patches that are present.
 * Once present, a feature is on unless the user turns it off there.
 *
 * Takes effect on the next Activity creation, so the toggles restart the app.
 */
public final class Shapes {
    public static final String PREFS = "ungoogled_ui";
    public static final String KEY_RECT = "rect_shapes";
    public static final String KEY_BLACK = "black_theme";
    public static final String KEY_HIDE_ADS = "hide_ads";
    private static final int MNC_RECT = 9999;
    private static final int MCC_BLACK = 9999;
    public static final float RADIUS_DP = 8f;

    // ---- which patches are present ------------------------------------------
    // Each returns false here and is rewritten to return true by its patch.

    public static boolean rectShapesPatched() { return false; }
    public static boolean blackThemePatched() { return false; }
    public static boolean navZoomPatched() { return false; }
    public static boolean hideAdsPatched() { return false; }
    public static boolean hideExplorePatched() { return false; }
    public static boolean hideTabsPatched() { return false; }
    public static boolean locationSourcePatched() { return false; }
    public static boolean proxyPatched() { return false; }
    public static boolean betterOfflinePatched() { return false; }
    /** Location provider toggle's option: rewritten to return true when it defaults to Play services. */
    public static boolean playLocationByDefault() { return false; }

    /** Cached toggle state; refreshed by wrap() at every Activity attach. */
    public static volatile boolean RECT = rectShapesPatched();
    public static volatile boolean BLACK = blackThemePatched();
    /** Read from patched Maps code as plain static fields. */
    public static volatile boolean HIDE_ADS = hideAdsPatched();
    public static volatile boolean HIDE_EXPLORE = hideExplorePatched();
    public static volatile boolean HIDE_TABS = hideTabsPatched();
    public static volatile boolean BETTER_OFFLINE = betterOfflinePatched();

    /** Set right before an account-sheet row opens something of ours, so the sheet's tap
     *  dispatcher returns NO_DISMISS once instead of closing the sheet behind it. */
    public static volatile boolean SKIP_DISMISS;

    /** The OneGoogle account-menu shower -- stashed every time the menu is shown -- and the
     *  "reopen the menu when this page closes" flag set by the account sheet's Settings row.
     *  Every fragment destroy is reported to fragmentDestroyed(); when the Settings page itself
     *  goes away, the menu comes back. */
    public static volatile Object MENU;
    public static volatile boolean REOPEN_MENU;
    private static final String[] SETTINGS_FRAGMENTS = {"avrz", "avrw"};   // Maps 26.36.04: new / legacy Settings page

    public static void fragmentDestroyed(Object fragment) {
        if (!REOPEN_MENU || fragment == null) return;
        String n = fragment.getClass().getName();
        for (String f : SETTINGS_FRAGMENTS) {
            if (n.equals(f)) { REOPEN_MENU = false; reopenAccountMenu(); return; }
        }
    }

    public static void reopenAccountMenu() {
        final Object menu = MENU;
        if (menu == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new ReopenMenu(menu), 250);
    }

    static final class ReopenMenu implements Runnable {
        private final Object menu;
        ReopenMenu(Object menu) { this.menu = menu; }
        @Override public void run() {
            try { menu.getClass().getMethod("b").invoke(menu); } catch (Throwable ignored) {}
        }
    }

    private static float radiusPx = -1f;

    private Shapes() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return rectShapesPatched() && prefs(c).getBoolean(KEY_RECT, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_RECT, on).commit();
        RECT = on && rectShapesPatched();
    }

    public static boolean blackEnabled(Context c) {
        return blackThemePatched() && prefs(c).getBoolean(KEY_BLACK, true);
    }

    public static boolean hideAdsEnabled(Context c) {
        return hideAdsPatched() && prefs(c).getBoolean(KEY_HIDE_ADS, true);
    }

    public static void setHideAdsEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HIDE_ADS, on).commit();
        HIDE_ADS = on && hideAdsPatched();
    }

    /**
     * Called first thing when any of the app's processes starts (the Application's
     * attachBaseContext), before its content providers, services or Activities exist.
     * Settings that must cover a whole process go here, not in wrap(): a process
     * started for a background job never creates an Activity, and a network stack
     * set up before the proxy properties exist would bypass the proxy for the rest
     * of the process's life.
     */
    public static void processStart(Context c) {
        try {
            refreshPlayLocation(c);
            applyProxy(c);
        } catch (Throwable ignored) {}
    }

    // ---- Hide explore sheet / Hide bottom tabs ------------------------------
    // Read by the home screen when it is built, so a change needs a restart.

    public static final String KEY_HIDE_EXPLORE = "hide_explore";
    public static final String KEY_HIDE_TABS = "hide_tabs";

    public static boolean hideExploreEnabled(Context c) {
        return hideExplorePatched() && prefs(c).getBoolean(KEY_HIDE_EXPLORE, true);
    }

    public static void setHideExploreEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HIDE_EXPLORE, on).commit();
        HIDE_EXPLORE = on && hideExplorePatched();
    }

    public static boolean hideTabsEnabled(Context c) {
        return hideTabsPatched() && prefs(c).getBoolean(KEY_HIDE_TABS, true);
    }

    public static void setHideTabsEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HIDE_TABS, on).commit();
        HIDE_TABS = on && hideTabsPatched();
    }

    // ---- Better offline maps -------------------------------------------------
    // The offline picker's own logic is in OfflinePicker; this is only its switch.
    // Read live by the picker, so it needs no restart.

    public static final String KEY_BETTER_OFFLINE = "better_offline";

    public static boolean betterOfflineEnabled(Context c) {
        return betterOfflinePatched() && prefs(c).getBoolean(KEY_BETTER_OFFLINE, true);
    }

    public static void setBetterOfflineEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_BETTER_OFFLINE, on).commit();
        BETTER_OFFLINE = on && betterOfflinePatched();
    }

    // ---- Location source -----------------------------------------------------
    //
    // Maps has two location-provider strategies and takes the first that says it
    // is available: Play services' fused provider, then Android's own
    // LocationManager. The patched fused-provider check ANDs its own answer with
    // playLocation(), and while that is false every Play services location
    // connection is pointed at a package that does not exist -- so "Android"
    // means Play services is never asked for a location at all.
    //
    // It is never true while Play services is missing or disabled. Bypass Play
    // Services checks makes the availability check succeed regardless, so Maps
    // would otherwise pick the fused provider with nothing behind it and never
    // get a fix -- the blue dot greys out and routing from your location hangs.

    public static final String KEY_PLAY_LOCATION = "play_location";
    static final String PLAY_SERVICES = "com.google.android.gms";
    private static volatile int PLAY_LOCATION = -1;   // -1 = not read yet

    /** The user's choice, whether or not Play services can honour it right now. */
    public static boolean playLocationEnabled(Context c) {
        return prefs(c).getBoolean(KEY_PLAY_LOCATION, playLocationByDefault());
    }

    public static void setPlayLocationEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_PLAY_LOCATION, on).commit();
        refreshPlayLocation(c);
    }

    /** Play services is installed and enabled. */
    public static boolean playServicesUsable(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(PLAY_SERVICES, 0).enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    static void refreshPlayLocation(Context c) {
        if (!locationSourcePatched()) return;
        PLAY_LOCATION = playLocationEnabled(c) && playServicesUsable(c) ? 1 : 0;
    }

    /** Called from the fused-provider availability check, possibly before any Activity exists. */
    public static boolean playLocation() {
        int v = PLAY_LOCATION;
        if (v < 0) {
            Context app = application();
            if (app == null) return false;   // not known yet: Android's own providers always work
            refreshPlayLocation(app);
            v = PLAY_LOCATION;
        }
        return v == 1;
    }

    /** Called with the package and service action of every Play services connection Maps opens. */
    public static String locationPackage(String pkg, String action) {
        if (action != null && action.startsWith("com.google.android.location.") && !playLocation()) {
            return "org.ungoogled.no.play.location";
        }
        return pkg;
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- Proxy ---------------------------------------------------------------
    //
    // An HTTP proxy for all of Maps' own traffic -- point it at Orbot's HTTP port
    // (127.0.0.1:8118) to route Maps over Tor. Play services runs in its own
    // process and is not covered. Stored OsmAnd-style as three parts (on, host,
    // port), folded into one effective "host:port" setting ("" = off).
    //
    // Two stacks carry Maps' traffic, and each needs its own mechanism. Plain
    // Java HTTP calls follow the JVM proxy properties set here. Cronet -- tiles,
    // search, place data, photos -- does not keep to those (it follows the
    // system proxy and uses QUIC), so CronetProxy hands every engine the proxy
    // explicitly, with no direct fallback. Both are applied when a process
    // starts, before anything opens a connection (processStart). A change made
    // while Maps runs cannot reach engines that already exist, so the Proxy
    // screen restarts Maps whenever it leaves the setting changed.

    public static final String KEY_PROXY = "proxy";
    public static final String KEY_PROXY_ON = "proxy_on", KEY_PROXY_HOST = "proxy_host", KEY_PROXY_PORT = "proxy_port";

    /** The effective setting: "host:port", or "" when off. */
    public static String proxy(Context c) {
        return prefs(c).getString(KEY_PROXY, "");
    }

    public static boolean proxyOn(Context c) {
        return prefs(c).getBoolean(KEY_PROXY_ON, false);
    }

    public static String proxyHost(Context c) {
        return prefs(c).getString(KEY_PROXY_HOST, "");
    }

    public static int proxyPort(Context c) {
        return prefs(c).getInt(KEY_PROXY_PORT, 0);
    }

    /** Stores the parts and folds them into the effective setting. */
    public static void setProxyParts(Context c, boolean on, String host, int port) {
        host = host == null ? "" : host.trim();
        String effective = on && !host.isEmpty() && port > 0 && port < 65536 ? host + ":" + port : "";
        prefs(c).edit()
                .putBoolean(KEY_PROXY_ON, on).putString(KEY_PROXY_HOST, host).putInt(KEY_PROXY_PORT, port)
                .putString(KEY_PROXY, effective).commit();
        applyProxy(c);
    }

    private static String PROXY_PROBED = "";
    private static volatile String PROXY_EFFECTIVE = "";
    private static volatile Context PROXY_CONTEXT;

    /** The proxy in force in this process, "host:port" or "" -- read by CronetProxy, which has no Context. */
    public static String proxyEffective() {
        return PROXY_EFFECTIVE;
    }

    /**
     * host:port -> the four JVM proxy properties, which Maps' plain Java HTTP calls
     * follow, and the value CronetProxy gives every Cronet engine; empty clears them.
     */
    static void applyProxy(Context c) {
        if (!proxyPatched()) return;
        String hp = "";
        try { hp = proxy(c); } catch (Throwable ignored) {}
        try {
            Context app = c.getApplicationContext();
            PROXY_CONTEXT = app != null ? app : c;   // no application context yet at process start
            int colon = hp.lastIndexOf(':');
            if (colon <= 0 || colon == hp.length() - 1) {
                System.clearProperty("http.proxyHost"); System.clearProperty("http.proxyPort");
                System.clearProperty("https.proxyHost"); System.clearProperty("https.proxyPort");
                PROXY_EFFECTIVE = "";
                PROXY_PROBED = "";
                return;
            }
            String host = hp.substring(0, colon).trim(), port = hp.substring(colon + 1).trim();
            int p = Integer.parseInt(port);
            System.setProperty("http.proxyHost", host);   System.setProperty("http.proxyPort", port);
            System.setProperty("https.proxyHost", host);  System.setProperty("https.proxyPort", port);
            PROXY_EFFECTIVE = host + ":" + p;
            // A typo or a stopped Orbot would otherwise show only as a map that no
            // longer loads (Cronet may not go direct), or -- for the plain Java calls,
            // which fall back to a direct connection -- as nothing at all. Probe it
            // once per value and say so out loud.
            if (!hp.equals(PROXY_PROBED)) {
                PROXY_PROBED = hp;
                new Thread(new ProxyProbe(host, p, hp)).start();
            }
        } catch (Throwable ignored) {}
    }

    private static final java.util.Set<String> WARNED = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** A long Toast, once per message per process. */
    static void warnProxy(String message) {
        if (!WARNED.add(message)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new ProxyWarn(message));
    }

    static final class ProxyProbe implements Runnable {
        private final String host; private final int port; private final String hp;
        ProxyProbe(String host, int port, String hp) {
            this.host = host; this.port = port; this.hp = hp;
        }
        @Override public void run() {
            boolean ok = false;
            java.net.Socket s = null;
            try {
                s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(host, port), 2500);
                ok = true;
            } catch (Throwable ignored) {
            } finally {
                try { if (s != null) s.close(); } catch (Throwable ignored) {}
            }
            if (!ok) warnProxy("Proxy " + hp + " is unreachable");
        }
    }

    static final class ProxyWarn implements Runnable {
        private final String message;
        ProxyWarn(String message) { this.message = message; }
        @Override public void run() {
            try {
                android.widget.Toast.makeText(PROXY_CONTEXT, message, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    /** Maps' navigation has its OWN colour scheme, independent of dark_mode: Settings ->
     *  Navigation -> "Color scheme" = Automatic | Day | Night. Automatic (the default) means
     *  DAY in daylight, so turn-by-turn went white in the middle of the black theme. The
     *  preference WIDGET's key is "color_scheme" but the stored setting is
     *  "force_night_mode_2", holding the enum name AUTO | FORCE_DAY | FORCE_NIGHT. */
    public static final String KEY_NAV_SCHEME = "force_night_mode_2";
    /** Maps' own theme + nav-scheme settings as they were BEFORE Black theme first
     *  overrode them, so switching Black off can put them back exactly. A key that
     *  did not exist at all is remembered as ABSENT and removed again on restore,
     *  which leaves Maps on its own default rather than one we invented. */
    public static final String KEY_PREV_DARK = "prev_dark_mode", KEY_PREV_NAV = "prev_nav_scheme";
    private static final String ABSENT = "\u0000absent";

    /** Capture once, and only once -- enforceDark runs on every Activity attach, so a
     *  second capture would record the values Black theme itself just wrote. */
    private static void saveTheme(Context c) {
        try {
            SharedPreferences ours = prefs(c);
            if (ours.contains(KEY_PREV_DARK)) return;
            SharedPreferences sp = c.getSharedPreferences("settings_preference", Context.MODE_PRIVATE);
            ours.edit().putString(KEY_PREV_DARK, sp.getString("dark_mode", ABSENT))
                       .putString(KEY_PREV_NAV, sp.getString(KEY_NAV_SCHEME, ABSENT)).commit();
        } catch (Throwable ignored) {}
    }

    private static void restoreTheme(Context c) {
        try {
            SharedPreferences ours = prefs(c);
            String d = ours.getString(KEY_PREV_DARK, null), n = ours.getString(KEY_PREV_NAV, null);
            SharedPreferences.Editor e = c.getSharedPreferences("settings_preference", Context.MODE_PRIVATE).edit();
            if (d == null || ABSENT.equals(d)) e.remove("dark_mode"); else e.putString("dark_mode", d);
            if (n == null || ABSENT.equals(n)) e.remove(KEY_NAV_SCHEME); else e.putString(KEY_NAV_SCHEME, n);
            e.commit();
            ours.edit().remove(KEY_PREV_DARK).remove(KEY_PREV_NAV).commit();
        } catch (Throwable ignored) {}
    }

    private static void enforceDark(Context c) {
        try {
            saveTheme(c);                       // always capture BEFORE the first overwrite
            SharedPreferences sp = c.getSharedPreferences("settings_preference", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = null;
            if (!"ON".equals(sp.getString("dark_mode", null))) {
                e = sp.edit(); e.putString("dark_mode", "ON");
            }
            if (!"FORCE_NIGHT".equals(sp.getString(KEY_NAV_SCHEME, null))) {
                if (e == null) e = sp.edit();
                e.putString(KEY_NAV_SCHEME, "FORCE_NIGHT");
            }
            if (e != null) e.commit();
        } catch (Throwable ignored) {}
    }

    /**
     * Also flips Maps' OWN theme setting: its dark-mode controller reads
     * SharedPreferences "settings_preference" / "dark_mode" as the enum name
     * FOLLOW_SYSTEM | ON | OFF and ignores the Activity Configuration's uiMode
     * entirely. Black needs the dark palette underneath, so ON pins
     * dark_mode=ON and the navigation scheme to FORCE_NIGHT. Switching Black
     * OFF puts back whatever the user had before, captured by saveTheme() on
     * the way in.
     */
    public static void setBlackEnabled(Context c, boolean on) {
        if (on) saveTheme(c);                   // capture the user's Light/Dark/System choice first
        prefs(c).edit().putBoolean(KEY_BLACK, on).commit();
        BLACK = on && blackThemePatched();
        if (on) enforceDark(c); else restoreTheme(c);
    }

    /**
     * Called from the Activity base class right before its super.attachBaseContext
     * (after the app's own locale wrapping). createConfigurationContext does NOT
     * merge nested overrides, so the full current configuration is copied and
     * only mcc/mnc are changed -- the app's locale override survives.
     */
    public static Context wrap(Context base) {
        try {
            RECT = enabled(base);
            BLACK = blackEnabled(base);
            HIDE_ADS = hideAdsEnabled(base);
            HIDE_EXPLORE = hideExploreEnabled(base);
            HIDE_TABS = hideTabsEnabled(base);
            BETTER_OFFLINE = betterOfflineEnabled(base);
            NAV_ZOOM_BUTTONS = navZoomEnabled(base);
            refreshPlayLocation(base);
            applyProxy(base);
            if (lastBottom <= 0) loadPlacement(base);
            startPopupTicker();
            if (!RECT && !BLACK) return base;
            Configuration c = new Configuration(base.getResources().getConfiguration());
            if (RECT) c.mnc = MNC_RECT;
            if (BLACK) {
                c.mcc = MCC_BLACK;   // night itself comes from Maps' own dark_mode setting, see setBlackEnabled
                enforceDark(base);   // ...which Maps' own Settings page could flip back: re-pin it on every Activity
            }
            // Maps applies its own theme setting to its AppCompat screens (Settings and its
            // pages) by updating each Activity's configuration after it is attached, and a
            // configuration context made here never takes that update: those screens kept the
            // system's day/night, so with Maps dark on a light system they drew dark labels on a
            // dark page (Settings -> Navigation). Carry Maps' own choice over instead.
            int night = mapsNightMode(base);
            if (night != 0) c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;
        }
    }

    /** The night bits for Maps' own theme setting (Settings -> Theme), or 0 when it follows the system. */
    static int mapsNightMode(Context c) {
        String mode = c.getSharedPreferences("settings_preference", Context.MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        if ("ON".equals(mode)) return Configuration.UI_MODE_NIGHT_YES;
        if ("OFF".equals(mode)) return Configuration.UI_MODE_NIGHT_NO;
        return 0;
    }

    /** Relaunch the app so every Activity is recreated with the new resources. */
    public static void restart(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        c.startActivity(i);
        Runtime.getRuntime().exit(0);
    }

    // ---- clamping ---------------------------------------------------------

    public static float r() {
        if (radiusPx < 0f) radiusPx = RADIUS_DP * Resources.getSystem().getDisplayMetrics().density;
        return radiusPx;
    }

    /** Non-zero radii are clamped down to r(); 0 (a square corner) stays 0. */
    public static float clamp(float v) {
        if (!RECT) return v;
        float r = r();
        return v > r ? r : v;
    }

    public static float[] clamp(float[] a) {
        if (!RECT || a == null) return a;
        float[] b = a.clone();
        for (int i = 0; i < b.length; i++) b[i] = clamp(b[i]);
        return b;
    }

    // ---- GradientDrawable -------------------------------------------------

    public static void setCornerRadius(GradientDrawable g, float radius) { g.setCornerRadius(clamp(radius)); }
    public static void setCornerRadii(GradientDrawable g, float[] radii) { g.setCornerRadii(clamp(radii)); }
    public static void setShape(GradientDrawable g, int shape) {
        if (RECT && shape == GradientDrawable.OVAL) {
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(r());
        } else {
            g.setShape(shape);
        }
    }

    // ---- Path -------------------------------------------------------------

    public static void addRoundRect(Path p, RectF rect, float rx, float ry, Path.Direction d) { p.addRoundRect(rect, clamp(rx), clamp(ry), d); }
    public static void addRoundRect(Path p, RectF rect, float[] radii, Path.Direction d) { p.addRoundRect(rect, clamp(radii), d); }
    public static void addRoundRect(Path p, float l, float t, float rt, float b, float rx, float ry, Path.Direction d) { p.addRoundRect(l, t, rt, b, clamp(rx), clamp(ry), d); }
    public static void addRoundRect(Path p, float l, float t, float rt, float b, float[] radii, Path.Direction d) { p.addRoundRect(l, t, rt, b, clamp(radii), d); }
    public static void addOval(Path p, RectF rect, Path.Direction d) {
        if (RECT) p.addRoundRect(rect, r(), r(), d); else p.addOval(rect, d);
    }
    public static void addOval(Path p, float l, float t, float rt, float b, Path.Direction d) {
        if (RECT) p.addRoundRect(l, t, rt, b, r(), r(), d); else p.addOval(l, t, rt, b, d);
    }
    public static void addCircle(Path p, float x, float y, float radius, Path.Direction d) {
        if (RECT) p.addRoundRect(x - radius, y - radius, x + radius, y + radius, r(), r(), d); else p.addCircle(x, y, radius, d);
    }

    // ---- Canvas -----------------------------------------------------------

    /**
     * Draw-time colour remap (Black theme). Colours that never pass through a
     * setColor/spec we intercept -- Material's elevation overlay for the map
     * FABs is computed inside the Material library and painted directly -- are
     * caught here, on the Paint, right before the framework fills a shape with
     * it. Text isn't drawn through these calls, vectors render natively, so
     * only fills change.
     */
    public static Paint paint(Paint p) {
        if (BLACK && p != null && p.getShader() == null) {
            int c = p.getColor(), m = color(c);
            if (m != c) p.setColor(m);
        }
        return p;
    }

    public static void drawRoundRect(Canvas c, RectF rect, float rx, float ry, Paint p) { c.drawRoundRect(rect, clamp(rx), clamp(ry), paint(p)); }
    public static void drawRoundRect(Canvas c, float l, float t, float rt, float b, float rx, float ry, Paint p) { c.drawRoundRect(l, t, rt, b, clamp(rx), clamp(ry), paint(p)); }
    public static void drawCircle(Canvas c, float x, float y, float radius, Paint p) {
        if (RECT) c.drawRoundRect(x - radius, y - radius, x + radius, y + radius, r(), r(), paint(p)); else c.drawCircle(x, y, radius, paint(p));
    }
    public static void drawOval(Canvas c, RectF rect, Paint p) {
        if (RECT) c.drawRoundRect(rect, r(), r(), paint(p)); else c.drawOval(rect, paint(p));
    }
    public static void drawOval(Canvas c, float l, float t, float rt, float b, Paint p) {
        if (RECT) c.drawRoundRect(l, t, rt, b, r(), r(), paint(p)); else c.drawOval(l, t, rt, b, paint(p));
    }
    public static void drawPath(Canvas c, Path path, Paint p) { c.drawPath(path, paint(p)); }
    /** The two round navigation controls (en-route Search and route options) are squared by
     *  the patch at BUILD time, not here: their face is a baked PNG alpha mask
     *  (ic_qu_fab_circle / ic_qu_fab_shadow), redrawn as a rounded square into -mnc9999
     *  variants. Maps' view DSL re-binds those views faster than any runtime sweep can hold
     *  them, so do not add one here. */
    public static void drawRect(Canvas c, RectF rect, Paint p) { c.drawRect(rect, paint(p)); }
    public static void drawRect(Canvas c, Rect rect, Paint p) { c.drawRect(rect, paint(p)); }
    public static void drawRect(Canvas c, float l, float t, float rt, float b, Paint p) { c.drawRect(l, t, rt, b, paint(p)); }

    // ---- Black theme: colour remap ------------------------------------------

    /**
     * Dark neutral greys -> black, alpha kept, everything else untouched
     * (max(R,G,B) < 0x4c and max-min < 0x0c, so tinted dark containers keep
     * their tint). The Black theme patch's resource pass uses the same table.
     *
     * Measured off the account sheet on device, Maps' own dark theme paints:
     *
     *   row / card CONTAINER  #0e0e0e   mx 0x0e   <- DARKER than the ground
     *   page / sheet GROUND   #1e1f20   mx 0x20
     *   dark SURFACES         #121212 .. #171717  search bar, buttons, sheets
     *   ELEVATED surfaces     0x27 and up, from Material's elevation overlay
     *
     * Under an AMOLED-black ground a container cannot be distinguished by going
     * darker, so that one level is lifted to a visible grey and EVERYTHING else
     * goes to pure black.
     *
     * THE TABLE MUST BE IDEMPOTENT -- color(color(x)) == color(x). The resource
     * pass rewrites the colour RESOURCES and this runs again at draw time, so a
     * surface routinely arrives here already remapped. Both outputs are fixed
     * points: 0x00 falls in the first band, and the lifted value is a MARKER
     * passed straight through.
     */
    private static final int CONTAINER = 0x2a;   // #2a2a2a: the lifted container, and a pass-through marker

    public static Object NAV_CAM;

    /** Called from Lblyt;->k() (updateSharedCameraMode) while guidance is live.
     *  Runs often, so the common path is a reference compare. It must NOT latch
     *  once per process: a second navigation session in the same process gets a
     *  NEW controller, and a cached one from the first session is detached --
     *  setOverrides on it returns Optional.empty() and the zoom silently stops
     *  working. Every test up to this point happened to start a fresh process,
     *  which is exactly why that would not have shown up. */
    /** Set every time blyt.k() runs, i.e. continuously while guidance is live.
     *  This is the name-free signal that navigation is up. */
    static volatile long navCamAt;

    public static void navCam(Object controller) {
        if (controller == null) return;
        navCamAt = android.os.SystemClock.uptimeMillis();
        if (controller != NAV_CAM) {
            NAV_CAM = controller;
            if (NAV_TRACE) android.util.Log.w("UA", "NAVCAM controller published: " + controller.getClass().getName());
            // Attach on THIS frame rather than waiting for the next tick: the
            // tiles were visibly appearing after the rest of the nav chrome.
            try { new android.os.Handler(android.os.Looper.getMainLooper()).post(new NavTicker()); }
            catch (Throwable t) { }
        }
        startNavTicker();
    }

    // ---- nav zoom: hold an override on the navigation camera --------------
    //
    // slot1 of SharedCameraControllerImpl.setOverrides is a ZOOM LEVEL: swept on
    // device, 11 shows a whole metro-area route and 21 is hard street level.
    // It must be HELD, because a single write is superseded by the navigation
    // camera's own recomputation within about a second. Holding it keeps the
    // camera FOLLOWING, which is the entire point: a pinch drives the generic
    // map camera and drops you out of follow, this does not.
    //
    // NAV_Z_DEFAULT is only a FALLBACK for the first press, used when the live
    // camera cannot be read. Maps' navigation zoom is speed-dependent, so there
    // is no single default to match -- 17 is simply what navLiveZoom() actually
    // reported in follow mode (16.99). An earlier guess of 19.5 made the opening
    // press jump about 11x inward, which is how the guess got caught.
    // NAV_Z_MAX is bjow's own ceiling (its constructor clamps zoom to [2, 21]).
    static final float NAV_Z_DEFAULT = 17f, NAV_Z_MIN = 11f, NAV_Z_MAX = 21f;
    static volatile Float navZoom;              // null = no override, Maps decides
    private static boolean navHolding;

    /** blyt.d -> provider.a() -> Lblyz; (SharedCameraControllerImpl), resolved
     *  FRESH every call. Caching it is what breaks the second navigation session
     *  in a process; at 4 Hz the reflection costs nothing worth saving. */
    static Object navShared() {
        Object nav = NAV_CAM;
        if (nav == null) return null;
        try {
            java.lang.reflect.Field f = nav.getClass().getDeclaredField("d");
            f.setAccessible(true);
            Object provider = f.get(nav);
            if (provider == null) return null;
            java.lang.reflect.Method a = provider.getClass().getMethod("a");
            a.setAccessible(true);
            return a.invoke(provider);
        } catch (Throwable t) { return null; }
    }

    /** Maps' own zoom-override entry point, SharedCameraControllerImpl.setZoomOverride:
     *
     *      Optional i(Float f) { return s(f, <existing slot2>, 5); }
     *
     *  Preferred over calling s() directly for two reasons. It PRESERVES the
     *  slot2 (tilt) override -- s() rebuilds the override proto from scratch, so
     *  passing null for slot2 there wipes any tilt Maps set, 4x a second -- and
     *  it uses Maps' own transition mode 5 rather than 1, which animates the
     *  change instead of snapping it. It is also independent confirmation that
     *  slot1 is the zoom: i() takes a single Float and forwards it as f.
     *
     *  s() returns Optional.empty() when no camera is attached, so isPresent()
     *  is a real success check, not just "did not throw". */
    static boolean navApply(Float zoom) { return navApply(zoom, false); }

    /** @param snap true = transition mode 1 (instant), for correcting drift;
     *              false = Maps' own mode 5, which animates, for a user press. */
    static boolean navApply(Float zoom, boolean snap) {
        Object z = navShared();
        if (z == null) return false;
        if (snap) {
            try {   // s(zoom, <existing tilt>, 1) -- i()'s body, but instant
                java.lang.reflect.Method k = z.getClass().getMethod("k");   // current tilt override
                k.setAccessible(true);
                Object tilt = k.invoke(z);
                java.lang.reflect.Method m = z.getClass().getMethod("s", Float.class, Float.class, int.class);
                m.setAccessible(true);
                Object r = m.invoke(z, zoom, tilt, Integer.valueOf(1));
                return Boolean.TRUE.equals(r.getClass().getMethod("isPresent").invoke(r));
            } catch (Throwable t) { /* fall through to the animated path */ }
        }
        try {
            java.lang.reflect.Method m = z.getClass().getMethod("i", Float.class);
            m.setAccessible(true);
            Object r = m.invoke(z, zoom);
            return Boolean.TRUE.equals(r.getClass().getMethod("isPresent").invoke(r));
        } catch (Throwable t) {
            try {   // fallback: the raw setter, slot2 not preserved
                java.lang.reflect.Method m = z.getClass().getMethod("s", Float.class, Float.class, int.class);
                m.setAccessible(true);
                Object r = m.invoke(z, zoom, null, Integer.valueOf(1));
                return Boolean.TRUE.equals(r.getClass().getMethod("isPresent").invoke(r));
            } catch (Throwable t2) { return false; }
        }
    }

    /** The zoom the navigation camera is actually at right now.
     *
     *  Maps' navigation zoom is DYNAMIC -- it pulls out as you speed up and
     *  pushes in on the approach to a turn -- so there is no single "default"
     *  to hardcode. Seeding the first press from the live value is what makes
     *  that press a nudge rather than a jump.
     *
     *      blyz.x  (blyw, the camera-position reader)
     *        -> blyw.d  (Optional<bjow>, refreshed every frame from
     *                    nativeGetCameraAnimatedPosition)
     *          -> bjow.q  (float zoom, clamped by bjow's ctor to [2, 21])
     *
     *  Null on any miss, and the caller falls back to NAV_Z_DEFAULT. */
    static Float navLiveZoom() {
        try {
            Object z = navShared();
            if (z == null) return null;
            java.lang.reflect.Field fx = z.getClass().getDeclaredField("x");
            fx.setAccessible(true);
            Object w = fx.get(z);
            if (w == null) return null;
            java.lang.reflect.Field fd = w.getClass().getDeclaredField("d");
            fd.setAccessible(true);
            Object opt = fd.get(w);
            if (opt == null || !Boolean.TRUE.equals(opt.getClass().getMethod("isPresent").invoke(opt))) return null;
            Object pos = opt.getClass().getMethod("get").invoke(opt);
            java.lang.reflect.Field fq = pos.getClass().getDeclaredField("q");
            fq.setAccessible(true);
            return Float.valueOf(fq.getFloat(pos));
        } catch (Throwable t) { return null; }
    }

    /** Called from Lblyt;->k() IMMEDIATELY AFTER its
     *  Lblyz;->e(Lcfoq;Lblti;Z) call -- the one line in updateSharedCameraMode
     *  that re-targets the camera.
     *
     *  Measured: k() runs about once a second and every drift event followed one
     *  within 150-270 ms, the camera animating off toward Maps' own zoom (11.6,
     *  13.8, 14.9 ...) while the user had asked for 11. Correcting that from a
     *  frame callback is inherently a frame late, which is what "it keeps trying
     *  to zoom in" looks like -- and worse, a correction issued mid-animation is
     *  reported applied while the live position does not move for ~240 ms.
     *  Re-asserting here instead cancels the re-target in the same call, before
     *  anything is drawn, so no zoom-in is ever rendered. */
    /** Called from Lblyt;->f(), the camera controller's teardown (it clears
     *  blyz.m and calls blyz.m()). Without it, "navigation ended" is only
     *  inferred from the camera hook going 3 s stale, so the tiles lingered for
     *  up to three seconds after the rest of the drive chrome had gone. */
    public static void navEnded() {
        navCamAt = 0L;
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) detachTiles();
            else new android.os.Handler(android.os.Looper.getMainLooper()).post(new Detach());
        } catch (Throwable t) { }
    }

    static final class Detach implements Runnable {
        public void run() { detachTiles(); }
    }

    public static void navReassert() {
        Float z = navZoom;
        if (z != null) navApply(z, true);
    }

    /** Reset: drop our override and hand the zoom back to Maps.
     *
     *  Not "set it to 17" -- Maps' navigation zoom is speed-dependent, so the
     *  default is a moving target and pinning any number would be a different
     *  override, not a reset. Clearing the slot lets the nav camera resume its
     *  own automatic zoom, which is what it was doing before the first press.
     *  Cleared with the ANIMATED transition so it eases back rather than jumps,
     *  and navZoom = null also stops the hold loop and makes navReassert() a
     *  no-op, so nothing of ours keeps touching the camera afterwards. */
    public static void navZoomReset() {
        if (navZoom == null) return;          // already Maps' own
        navZoom = null;
        navApply(null, false);
    }

    public static void navZoomStep(float delta) {
        Float cur = navZoom;
        if (cur == null) cur = navLiveZoom();          // first press: nudge from where we are
        float v = (cur == null ? NAV_Z_DEFAULT : cur.floatValue()) + delta;
        if (v < NAV_Z_MIN) v = NAV_Z_MIN;
        if (v > NAV_Z_MAX) v = NAV_Z_MAX;
        navZoom = Float.valueOf(v);
        boolean ok = navApply(navZoom);
        if (NAV_TRACE) android.util.Log.w("UA", "NAVZOOM step " + delta + " -> " + v + " applied=" + ok);
        if (!navHolding) { navHolding = true; new NavHold().run(); }
    }

    /** Maps recomputes its own goal zoom on every guidance update, so a held
     *  override loses ground between re-applies. At 250 ms with Maps' ANIMATED
     *  transition that reads as the camera "creeping back in" and refusing to
     *  stay zoomed out -- reported from a live drive.
     *
     *  So: correct every FRAME rather than 4x a second, only when the live camera
     *  has actually drifted from the target, and SNAP when correcting (mode 1)
     *  instead of starting a fresh animation each time. A user press still
     *  animates; only the maintenance is instant, which is invisible because the
     *  camera is already where we are putting it. */
    /** How far the camera may drift before we correct it, and how often we look.
     *
     *  These were 0.02 and every FRAME, which meant re-writing the camera
     *  override up to 60x a second. Maps itself only ever writes it on a
     *  discrete user action -- every caller of blys.h/z in the app is feature
     *  code, never a loop -- and each write re-targets the camera's transition
     *  with a fresh timestamp. Hammering it fights the follow logic, which shows
     *  up as the camera wandering off after the FIRST zoom press (the press is
     *  what starts the hold). The in-call re-assert from blyt.k() is what
     *  actually stops the drift; this is only a backstop, so it can be slow and
     *  tolerant. */
    static final float NAV_Z_EPS = 0.12f;
    static final long  NAV_HOLD_MS = 400L;
    /** Diagnostic only: logs target vs live zoom while holding, and every
     *  blyt.k() call. Flip to true to re-run the correlation that found the
     *  re-target (tracks.md 6af). */
    static boolean NAV_TRACE = false;

    static final class NavHold implements Runnable, android.view.Choreographer.FrameCallback {
        // NOT an anonymous FrameCallback: d8 NPEs on anonymous classes in this
        // build and silently leaves a STALE classes11.dex (tracks.md 6ab).
        public void doFrame(long frameTimeNanos) { run(); }

        int tick;
        public void run() {
            Float z = navZoom;
            if (z == null) { navHolding = false; return; }   // nothing to hold; navZoomStep restarts us
            try {
                Float live = navLiveZoom();
                boolean drift = live == null || Math.abs(live.floatValue() - z.floatValue()) > NAV_Z_EPS;
                boolean ok = drift ? navApply(z, true) : true;
                if (NAV_TRACE && (++tick % 12) == 0)
                    android.util.Log.w("UA", "NAVHOLD target=" + z + " live=" + live
                            + (drift ? " DRIFT applied=" + ok : " ok"));
            } catch (Throwable t) { }
            try { new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, NAV_HOLD_MS); }
            catch (Throwable t) { navHolding = false; }
        }
    }

    // ---- navigation zoom tiles ---------------------------------------------
    //
    // Two tiles, plus and minus, sitting above the speed badge and styled like
    // Maps' own navigation buttons. They are added to the DECOR VIEW, not into
    // the navigation layout: Maps' view DSL rebinds its own subtree constantly
    // and silently discards anything injected there, which defeated an earlier
    // attempt on this project. The decor view is the Activity's, so our tile
    // survives. Measured: a test tile added this way rendered and persisted
    // across the whole navigation session.
    public static final String KEY_NAV_ZOOM = "nav_zoom_buttons";
    public static volatile boolean NAV_ZOOM_BUTTONS = navZoomPatched();

    public static boolean navZoomEnabled(Context c) {
        return navZoomPatched() && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NAV_ZOOM, true);
    }

    public static void setNavZoomEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NAV_ZOOM, on).commit();
        NAV_ZOOM_BUTTONS = on && navZoomPatched();
    }

    static java.util.List<android.view.View> rootViews() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object res = f.get(inst);
            if (res instanceof java.util.List) return (java.util.List<android.view.View>) res;
        } catch (Throwable t) { }
        return java.util.Collections.emptyList();
    }

    private static int navContainerId;

    /** Resolve "nav_container" to an int ONCE. The first version compared
     *  getResourceEntryName(id) against a string for every view carrying an id,
     *  on every one-second tick -- a resource-table lookup per view per second
     *  across the whole tree. */
    /** Resolve an id by name, trying the qualified spellings too. Returns 0 if
     *  the name cannot be resolved -- which DOES happen: on a Samsung SM-F766U1
     *  running the merged universal APK this returned 0 for "nav_container"
     *  every time, while the same build resolved it fine on the emulator from
     *  split APKs. Nothing downstream may depend on it. */
    static int idOf(android.view.View v, String name) {
        try {
            android.content.res.Resources res = v.getResources();
            String pkg = v.getContext().getPackageName();
            int id = res.getIdentifier(name, "id", pkg);
            if (id == 0) id = res.getIdentifier(pkg + ":id/" + name, null, null);
            if (id == 0) id = res.getIdentifier("com.google.android.apps.maps:id/" + name, null, null);
            return id;
        } catch (Throwable t) { return 0; }
    }

    /** Deliberately takes no name: the resolved id is cached in a single static,
     *  so a second caller with a different name would silently get this one. */
    /** Depth-first search for a shown view with this id. */
    static android.view.View findViewNamed(android.view.View v, int id) {
        if (id == 0) return null;
        try { if (v.getId() == id && v.isShown()) return v; } catch (Throwable t) { }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findViewNamed(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int navFabId;

    /** One button of Maps' own right-hand nav stack. It is built from the SAME
     *  fab mask as our tiles, so its view box has the same transparent padding
     *  and aligning view-to-view puts our rows exactly on its rows. */
    static android.view.View findNavFab(android.view.View decor) {
        if (navFabId == 0) navFabId = idOf(decor, "nav_search_fab_button");
        return findViewNamed(decor, navFabId);
    }

    static android.view.View findNavContainer(android.view.View v) {
        int want = navContainerId;
        if (want == 0) {
            want = navContainerId = idOf(v, "nav_container");
            if (want == 0) return null;
        }
        try {
            if (v.getId() == want && v.isShown()) return v;
        } catch (Throwable t) { }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findNavContainer(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The speed badge, found by its own unit label, so the tiles sit above whatever
     *  Maps actually drew rather than at a guessed offset. */
    static android.view.View findSpeedBadge(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null) {
                String s2 = t.toString().trim().toLowerCase(java.util.Locale.US);
                // "km/h" but also km/t, km/tim, km/u ... plus mph. Locale-safe enough
                // without carrying a table of every unit string Maps ships.
                if ((s2.startsWith("km/") || "mph".equals(s2)) && v.isShown()) {
                    speedUnit = (android.widget.TextView) v;   // its colour = the live nav scheme
                    return v;                                  // the LABEL; badgeCard() finds the card
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findSpeedBadge(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The badge CARD, found by walking up from the "mph" label to the largest
     *  ancestor that is still badge-sized.
     *
     *  Taking the label's immediate parent is not stable: which view that is
     *  depends on what the badge is currently showing. It already put the column
     *  116px too far right once (fixed by pinning the left to the gutter), and
     *  then put it 200px too LOW -- the tiles landed on top of the speedometer --
     *  because the parent it found that time sat at y=2126 rather than the card's
     *  1926. Walking up to the card itself fixes both axes at the source. */
    static android.view.View badgeCard(android.view.View label, android.view.View decor) {
        android.view.View best = label;
        int maxW = decor.getWidth() / 2, maxH = decor.getHeight() / 4;
        if (maxW <= 0 || maxH <= 0) return best;
        android.view.ViewParent p = label.getParent();
        while (p instanceof android.view.View) {
            android.view.View v = (android.view.View) p;
            if (v == decor || v.getWidth() > maxW || v.getHeight() > maxH) break;
            best = v;
            p = v.getParent();
        }
        return best;
    }

    /** The speed badge's unit label, kept so the tiles can take their colours from
     *  the scheme Maps ACTUALLY drew. Navigation has its own colour scheme,
     *  independent of dark_mode (tracks.md 6aa: Automatic = DAY in daylight), so
     *  keying the tiles off our own BLACK flag gets it wrong whenever the two
     *  disagree -- e.g. Black off, Dark on, driving at noon. */
    static android.widget.TextView speedUnit;

    /** True when the nav screen is on a dark scheme, decided by the brightness of
     *  the text Maps rendered rather than by any preference of ours. */
    static boolean navDarkScheme() {
        android.widget.TextView u = speedUnit;
        if (u == null) return BLACK;
        try {
            int c = u.getCurrentTextColor();
            int lum = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
            return lum > 0x80;                    // light text => dark background
        } catch (Throwable t) { return BLACK; }
    }

    /** Maps' own fab mask pair, so our tiles carry the SAME halo as the
     *  neighbouring nav buttons instead of a Material elevation shadow that only
     *  approximates it. Both are 192x192 pure-black LA PNGs (measured): the
     *  face's alpha is a solid 168px disc with 12px padding, tinted at runtime;
     *  the shadow's alpha is a wider, softer disc peaking at 48% and is already
     *  black, so it is drawn untinted -- tinting would flatten its falloff.
     *
     *  Using the resources also makes the SHAPE follow Rectangle Shapes for
     *  free: the Rectangle shapes patch emits -mnc9999 variants of these same two ids,
     *  and the Activity's override Configuration selects them. */
    // The pinned ids of the two masks for this Maps build. Needed because the
    // MERGED universal APK (APKEditor) keeps the entries and the PNG files but
    // STRIPS their names from the resource table, and renames the table's
    // package back to com.google.android.apps.maps -- so getIdentifier() returns
    // 0 on a phone running the merged APK while the same build resolves the name
    // fine on the emulator from split APKs. Maps' own code never notices because
    // it compiles to numeric ids; doing the same is the fix. (aapt shows these
    // as "INVALID RESOURCE 0x7f0807c2" in the merged APK: entry present, name
    // gone.) 0x7f0807c2 == 2131232706, the id recorded in CLAUDE.md.
    static final int ID_FAB_FACE = 0x7f0807c2, ID_FAB_SHADOW = 0x7f0807c3;

    /** The face plus its halo, drawn.
     *
     *  Maps' glow was measured off its own buttons: the map ramps from
     *  (31,60,90) to (133,150,176) over ~18px before the black face -- i.e. a
     *  soft blur reaching about 48% white. The two shipped masks cannot
     *  reproduce that: drawn at matching bounds the face covers all but ~4px of
     *  the shadow's ramp (a hard band), and insetting the face to show more puts
     *  the visible part on the shadow's flat plateau (a grey slab). Elevation
     *  alone is invisible on a dark map. A blurred copy of the face shape is
     *  what actually matches, and it is tunable in one number.
     *
     *  Needs a software layer -- BlurMaskFilter is ignored under hardware
     *  acceleration. */
    static final class FabBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float glow, corner, density;
        private final boolean round;
        private final int colour, glowColour, outlineColour;

        FabBg(float density, int colour, boolean dark, boolean round, float cornerPx) {
            this.glow = GLOW_DP * density;
            this.density = density;
            this.colour = colour;
            this.round = round;
            this.corner = cornerPx;
            // ~30%. Measured 4px out from the face edge over the same background:
            // Maps' glow lifts the map by +24, ours lifted it by +46 at 0x8C and
            // +85 at full white. Note their reading VARIES between frames (+24 to
            // +117) because map content sits behind it, so this is matched to the
            // quieter, more representative sample rather than the brightest one.
            this.glowColour = dark ? 0x4DFFFFFF : 0x4D000000;
            this.outlineColour = dark ? 0x80FFFFFF : 0x26000000;
        }

        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            android.graphics.RectF r = new android.graphics.RectF(
                    b.left + glow, b.top + glow, b.right - glow, b.bottom - glow);
            float cr = round ? r.height() / 2f : corner;
            p.setMaskFilter(new android.graphics.BlurMaskFilter(glow, android.graphics.BlurMaskFilter.Blur.NORMAL));
            p.setColor(glowColour);
            c.drawRoundRect(r, cr, cr, p);
            p.setMaskFilter(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(colour);
            c.drawRoundRect(r, cr, cr, p);
            // A thin bright rim sits between the glow and the face on Maps' own
            // buttons: sampling across one, the glow tops out at lum 106 and then
            // a single ~2px band reads 145 before the black face at 0. Without it
            // ours steps straight from glow to face and looks flatter.
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(OUTLINE_DP * density);
            p.setColor(outlineColour);
            c.drawRoundRect(r, cr, cr, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }

        public void setAlpha(int a) { p.setAlpha(a); }
        public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static android.graphics.drawable.Drawable fabBackground(Context ctx, int colour, boolean dark) {
        try {
            android.content.res.Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();
            maskOk = Boolean.TRUE;
            float d = res.getDisplayMetrics().density;
            return new FabBg(d, colour, dark, !RECT, RECT ? r() : 0f);
        } catch (Throwable t) { maskOk = Boolean.FALSE; return null; }
    }

    /** Null until the first tile is built. */
    static Boolean maskOk;

    /** The view box for one tile. With the mask it is the 64dp canvas, whose
     *  face draws at 56dp; without it our own shape fills the view, so the view
     *  must BE 56dp or the tile comes out a full 8dp larger than Maps' buttons --
     *  which is exactly how it looked on the phone once the mask failed to load. */
    static int tileSide(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round((Boolean.FALSE.equals(maskOk) ? FACE_DP : CANVAS_DP) * d);
    }

    static int tilePad(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Boolean.FALSE.equals(maskOk) ? 0 : Math.round(PAD_DP * d);
    }

    // Same size as the neighbouring nav buttons, not smaller. Those controls use
    // a 64dp mask canvas (fab-circle-square.py FAB_DP), and the mask's face is
    // 168/192 of its canvas, so the VISIBLE face is 56dp. Sizing our view to the
    // same 64dp canvas therefore gives an identical face, an identical corner
    // radius (it is baked into the same PNG) and an identical halo.
    static final float FACE_DP = 56f;                       // same as Maps' nav buttons
    static final float MASK_DP = FACE_DP * 192f / 168f;     // 64: canvas giving a 56dp face
    /** Unused now that the halo comes from elevation, as Maps' own buttons do.
     *  Kept at 0 so the view box is exactly the mask canvas (face = 56dp). */
    /** Blur radius = the halo's width. MEASURED off Maps' own buttons: their glow
     *  runs x=866..881 before the face at 882, i.e. exactly 16px, which at this
     *  density is 6.1dp. 7dp put ours at ~18px and read as too much bloom. */
    static final float GLOW_DP = 6f;
    /** The thin bright rim at the face's edge -- ~2px on Maps' buttons. */
    static final float OUTLINE_DP = 0.8f;
    static final float RIM_DP = GLOW_DP;
    // FabBg draws the face inset by the glow, so the view box is face + glow on
    // each side -- NOT the mask canvas plus glow, which made the face 64dp
    // instead of 56 and the boxes visibly too big.
    static final float CANVAS_DP = FACE_DP + 2f * GLOW_DP;  // 70dp -> 56dp face
    static final float PAD_DP = (CANVAS_DP - FACE_DP) / 2f;

    /** The reset icon, drawn rather than taken from a font.
     *
     *  U+21BA rendered with the gap off-centre to the right and a thin, spiky
     *  arrowhead pointing up-left, which reads as tilted next to the flat "+"
     *  and "-". Drawing it gives an even stroke that matches their weight, a
     *  gap centred exactly at twelve o'clock, and a solid arrowhead. Bounds are
     *  ignored apart from their centre, so it can be set as a FILL foreground
     *  and still stay the right size.
     *
     *  Named class, not anonymous: d8 NPEs on anonymous classes in this build. */
    static final class ResetIcon extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint stroke =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path head = new android.graphics.Path();
        private final float d;

        ResetIcon(float density, int colour) {
            this.d = density;
            stroke.setColor(colour);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(3.4f * density);
            stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            fill.setColor(colour);
            fill.setStyle(android.graphics.Paint.Style.FILL);
        }

        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = 10.5f * d;
            // Canvas angles: 0 = 3 o'clock, -90 = 12 o'clock. A 70-degree gap
            // centred on 12 o'clock means the arc runs -55 clockwise by 290.
            android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, -55f, 290f, false, stroke);

            double end = Math.toRadians(-55f + 290f);            // where the stroke stops
            float ex = cx + r * (float) Math.cos(end), ey = cy + r * (float) Math.sin(end);
            float tx = -(float) Math.sin(end), ty = (float) Math.cos(end);   // clockwise tangent
            float nx = -ty, ny = tx;
            float a = 4.6f * d;
            head.reset();
            head.moveTo(ex + tx * a * 1.5f, ey + ty * a * 1.5f);  // tip, pointing into the gap
            head.lineTo(ex + nx * a, ey + ny * a);
            head.lineTo(ex - nx * a, ey - ny * a);
            head.close();
            c.drawPath(head, fill);
        }

        public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
        public void setColorFilter(android.graphics.ColorFilter cf) {
            stroke.setColorFilter(cf); fill.setColorFilter(cf);
        }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** @param glyph the character to draw, or null for the drawn reset icon. */
    static android.view.View tile(Context ctx, String glyph, float dx) {
        int side = tileSide(ctx);
        android.widget.TextView t = new android.widget.TextView(ctx);
        if (glyph != null) t.setText(glyph);
        t.setGravity(android.view.Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32f);   // scales with the 56dp face
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        boolean dark = navDarkScheme();
        t.setTextColor(dark ? 0xFFFFFFFF : 0xFF1F1F1F);
        // Pure black only where the Black theme has already made the neighbouring
        // nav buttons pure black; Maps' own dark nav scheme uses a grey surface,
        // and matching it is the whole point of sitting in that button column.
        int colour = dark ? (BLACK ? 0xFF000000 : 0xFF202124) : 0xFFFFFFFF;
        android.graphics.drawable.Drawable fab = fabBackground(ctx, colour, dark);
        if (fab != null) {
            t.setBackground(fab);
            // BlurMaskFilter is ignored under hardware acceleration.
            t.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        } else {                           // fallback: our own shape + a Material shadow
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setColor(colour);
            bg.setCornerRadius(RECT ? r() : side / 2f);
            t.setBackground(bg);
            t.setElevation(6f * ctx.getResources().getDisplayMetrics().density);
        }
        t.setOnClickListener(new ZoomClick(dx));
        if (glyph == null) {
            // FILL gravity is fine: ResetIcon uses only the centre of its bounds.
            t.setForeground(new ResetIcon(ctx.getResources().getDisplayMetrics().density,
                                          dark ? 0xFFFFFFFF : 0xFF1F1F1F));
        }
        t.setContentDescription(dx > 0 ? "Zoom in" : dx < 0 ? "Zoom out" : "Reset zoom");
        return t;
    }

    static final class ZoomClick implements android.view.View.OnClickListener {
        final float dx;                        // 0 = reset to Maps' automatic zoom
        ZoomClick(float dx) { this.dx = dx; }
        public void onClick(android.view.View v) {
            if (dx == 0f) navZoomReset(); else navZoomStep(dx);
        }
    }

    /** LayoutParams with the leading margin that produces our visible gap.
     *  A helper, not an anonymous subclass: d8 NPEs on anonymous classes here. */
    static android.widget.LinearLayout.LayoutParams lmargin(int side, int gap, boolean horizontal) {
        android.widget.LinearLayout.LayoutParams p =
                new android.widget.LinearLayout.LayoutParams(side, side);
        if (horizontal) p.leftMargin = gap; else p.topMargin = gap;
        return p;
    }

    static android.view.View navTiles;

    private static int navAttachTries;
    /** Where the tiles last sat. The speed badge we position against is not in
     *  the view tree until ~3s into guidance -- measured -- so waiting for it
     *  made the tiles appear noticeably after the rest of the nav chrome. With a
     *  remembered position they go up immediately and are corrected in place if
     *  the badge turns out to be somewhere else. */
    private static int lastLeft = -1, lastBottom = -1;

    static final String KEY_TILE_LEFT = "nav_tile_left", KEY_TILE_BOTTOM = "nav_tile_bottom";

    /** Remember it across process restarts too, so the FIRST navigation of a
     *  session is instant rather than the second. Only ever written from a
     *  badge-derived position, never from the cached guess. */
    static void savePlacement(Context c, int left, int bottom) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
             .putInt(KEY_TILE_LEFT, left).putInt(KEY_TILE_BOTTOM, bottom).apply();
        } catch (Throwable t) { }
    }

    static void loadPlacement(Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int l = sp.getInt(KEY_TILE_LEFT, -1), b = sp.getInt(KEY_TILE_BOTTOM, -1);
            if (l >= 0 && b > 0) { lastLeft = l; lastBottom = b; }
        } catch (Throwable t) { }
    }
    // Landscape is decided from the DECOR's own width/height, never from
    // Configuration.orientation: --rect-shapes gives every Activity an override
    // Configuration (Lef;/applyOverrideConfiguration), so that field is ours and
    // is not a trustworthy report of the screen. Measured: after a real rotation
    // the orientation field compared EQUAL and the re-attach never fired.
    private static boolean navLandscape;

    static void attachTiles(android.view.ViewGroup decor) {
        if (navTiles != null && navTiles.getParent() == decor) { repositionTiles(decor); return; }
        Context ctx = decor.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        // The mask has its own transparent padding, so the VIEW is bigger than the
        // 46dp face it draws. Every margin below is therefore corrected by `pad`,
        // so what lines up on screen is the visible face, not the view box.
        // Same grid as Maps' own nav button column, measured off the screen:
        // faces 146px apart vertically (21dp) and 53px (20dp) in from the screen
        // edge. Ours mirrors that margin on the left, so the two columns sit on
        // the same rhythm instead of ours being tighter and closer to the edge.
        // Probe the mask BEFORE sizing anything: fabBackground() is what
        // discovers whether the mask is loadable on this install, and the view
        // box depends on the answer (64dp canvas with it, 56dp without).
        if (maskOk == null) fabBackground(ctx, 0xFF000000, true);
        int pad    = tilePad(ctx);
        int side   = tileSide(ctx);
        int vgap   = Math.round(21f * d);                      // face-to-face gap, as theirs
        // 16dp, matching the SPEED BADGE directly below us -- not the 20dp the
        // right-hand stack uses. Maps' own layout is not symmetric: measured,
        // the badge's face starts at x=43 (16dp) while that stack sits ~20dp in
        // from the right edge. The badge is the control in OUR column, so it
        // governs x; the stack opposite governs the row rhythm (y) instead.
        int gutter = Math.round(16f * d);
        int gap    = Math.max(0, vgap - 2 * pad);              // margin -> that visible gap
        android.view.View label = findSpeedBadge(decor);   // first: it sets speedUnit, which tile() reads
        android.view.View badge = label == null ? null : badgeCard(label, decor);
        // The badge is what we position against AND what tells us the colour
        // scheme, and it is not necessarily laid out on the tick that first sees
        // nav_container. Give it a few seconds rather than silently settling for
        // the guessed position and the wrong colours.
        boolean haveCached = lastLeft >= 0 && lastBottom > 0;
        if ((label == null || decor.getHeight() <= 0) && !haveCached && navAttachTries < 5) {
            navAttachTries++; return;
        }
        // LANDSCAPE lays the pair out side by side. Stacked, the column is
        // 2*46+10 = 102dp tall, and in landscape there is only about 100dp
        // between the speed badge and the turn card -- measured: the "+" tile
        // drew ON TOP of the card. Side by side it is 46dp tall and clears it.
        boolean land = decor.getWidth() > decor.getHeight();
        android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
        col.setOrientation(land ? android.widget.LinearLayout.HORIZONTAL
                                : android.widget.LinearLayout.VERTICAL);
        // minus on the left reading left-to-right, plus above reading bottom-up:
        // either way "+" is the one nearer the top/right, as on Maps' own controls
        android.view.View plus  = tile(ctx, "+", +1f),
                          minus = tile(ctx, "\u2212", -1f),
                          reset = tile(ctx, null, 0f);        // null glyph -> the drawn ResetIcon
        // Portrait reads top-to-bottom reset / + / -, landscape is that same
        // order laid out bottom-to-top, i.e. - / + / reset left to right, so the
        // two orientations stay consistent with each other.
        if (land) {
            col.addView(minus, new android.widget.LinearLayout.LayoutParams(side, side));
            col.addView(plus,  lmargin(side, gap, true));
            col.addView(reset, lmargin(side, gap, true));
        } else {
            col.addView(reset, new android.widget.LinearLayout.LayoutParams(side, side));
            col.addView(plus,  lmargin(side, gap, false));
            col.addView(minus, lmargin(side, gap, false));
        }

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        // LEFT is a fixed 16dp gutter, NOT taken from the badge. findSpeedBadge
        // returns the parent of the "mph" label, and which view that is depends
        // on what the badge is currently showing -- with the speed-limit sign up
        // it can be an inner container indented well inside the card. Measured:
        // the badge card's own left edge is 16dp, exactly this gutter, so this
        // is both the correct alignment and a stable one. Observed failure: a
        // re-attach mid-navigation landed the column at left=158 instead of 42.
        int left = gutter - pad, bottom = Math.round(150f * d);
        int step = side + gap;                               // one row of the grid
        if (badge != null && decor.getHeight() > 0) {
            int[] at = new int[2]; badge.getLocationOnScreen(at);
            // at[1] is the badge's VIEW top, which carries the same transparent
            // mask padding our own views do -- so the margin between the two
            // VIEW boxes that yields a 21dp visible gap is `gap`, not
            // `vgap - pad`. Getting that wrong sat the column ~14px high and is
            // why its rows did not line up with the stack opposite.
            int b = decor.getHeight() - at[1] + gap;
            int lo = Math.round(80f * d), hi = Math.round(decor.getHeight() * 0.7f);
            if (b >= lo && b <= hi) bottom = b;
        }
        // Phase-lock onto Maps' own button stack so the two columns share rows.
        // Only the phase is taken, not the row index: we do not need to know
        // which button was found, just where its grid falls.
        android.view.View fab = land ? null : findNavFab(decor);
        if (fab != null && fab.getHeight() > 0 && step > 0) {
            int[] fat = new int[2]; fab.getLocationOnScreen(fat);
            int colTop = decor.getHeight() - bottom - (3 * side + 2 * gap);
            int delta = ((fat[1] - colTop) % step + step) % step;
            if (delta > step / 2) delta -= step;             // nearest row, either way
            if (Math.abs(delta) <= gap) bottom -= delta;     // only a nudge, never a jump
        }
        if (badge == null && haveCached) { left = lastLeft; bottom = lastBottom; }
        if (badge != null && (left != lastLeft || bottom != lastBottom)) savePlacement(ctx, left, bottom);
        lastLeft = left; lastBottom = bottom;
        lp.leftMargin = left; lp.bottomMargin = bottom;
        decor.addView(col, lp);
        navTiles = col;
        navLandscape = land;
        navAttachTries = 0;   // so a LATER attach (rotation -> new decor) waits for the badge too
        if (NAV_TRACE) android.util.Log.w("UA", "NAVZOOM tiles attached at left=" + left + " bottom=" + bottom
                + (land ? " landscape/side-by-side" : " portrait/stacked")
                + (badge == null ? " (no speed badge found, using fallback)" : " (above the speed badge)"));
    }

    /** Remove the tiles but keep navZoom and NAV_CAM, so the held zoom survives. */
    static void hideTiles() {
        android.view.View t = navTiles;
        navTiles = null;
        if (t == null) return;
        try {
            android.view.ViewParent p = t.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(t);
        } catch (Throwable th) { }
        speedUnit = null;
        navAttachTries = 0;
    }

    /** Once the speed badge finally appears, move the tiles onto it if the
     *  position we guessed from the cache was wrong. */
    static void repositionTiles(android.view.ViewGroup decor) {
        try {
            android.view.View lbl = findSpeedBadge(decor);
            if (lbl == null || decor.getHeight() <= 0) return;
            android.view.View card = badgeCard(lbl, decor);
            float d = decor.getContext().getResources().getDisplayMetrics().density;
            int pad = tilePad(decor.getContext());
            int gap = Math.max(0, Math.round(21f * d) - 2 * pad);
            int[] at = new int[2]; card.getLocationOnScreen(at);
            int b = decor.getHeight() - at[1] + gap;
            int lo = Math.round(80f * d), hi = Math.round(decor.getHeight() * 0.7f);
            if (b < lo || b > hi) return;
            android.view.ViewGroup.LayoutParams lp0 = navTiles.getLayoutParams();
            if (!(lp0 instanceof android.widget.FrameLayout.LayoutParams)) return;
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) lp0;
            if (Math.abs(lp.bottomMargin - b) <= 2) return;      // already right
            lp.bottomMargin = b; lastBottom = b;
            navTiles.setLayoutParams(lp);
        } catch (Throwable t) { }
    }

    static void detachTiles() {
        android.view.View t = navTiles;
        navTiles = null;
        if (t == null) return;
        try {
            android.view.ViewParent p = t.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(t);
        } catch (Throwable th) { }
        navZoom = null;
        navApply(null);          // clear the override BEFORE dropping the controller
        speedUnit = null;        // a static View reference would pin the Activity
        navAttachTries = 0;
        NAV_CAM = null;          // blyt.k() republishes on the next session
    }

    /** One second tick: show the tiles only while the navigation screen is up. */
    static final class NavTicker implements Runnable {
        public void run() {
            try {
                // Navigation is live if the camera hook fired recently. That is a
                // far more reliable signal than looking up nav_container by name,
                // which resolves to 0 on some installs (see idOf) and left the
                // tiles permanently un-attached on a real phone.
                java.util.List<android.view.View> roots = rootViews();
                boolean navUp = (android.os.SystemClock.uptimeMillis() - navCamAt) < 3000L;
                android.view.ViewGroup decor = navUp ? pickDecor(roots) : null;
                if (decor == null) navUp = false;
                // One line that answers "why are there no tiles": whether the
                // toggle is on, whether any window was enumerated at all, whether
                // the nav screen was recognised, and how big that window is.
                if (NAV_TRACE && (++navTick % 3) == 0)
                    android.util.Log.w("UA", "NAVZOOM tick enabled=" + NAV_ZOOM_BUTTONS
                            + " roots=" + roots.size() + " navContainerId=" + navContainerId
                            + " navUp=" + navUp + " tiles=" + (navTiles != null)
                            + " tries=" + navAttachTries
                            + (decor != null ? " decor=" + decor.getWidth() + "x" + decor.getHeight() : ""));
                if (!NAV_ZOOM_BUTTONS && navTiles == null) { reschedule(); return; }  // nothing to do
                boolean guidance = (android.os.SystemClock.uptimeMillis() - navCamAt) < 3000L;
                if (navUp && NAV_ZOOM_BUTTONS && decor != null) {
                    // Maps does not recreate the navigation Activity on rotation,
                    // so the DecorView and our margins survive it. Rebuild.
                    if (navTiles != null && decor.getWidth() > 0
                            && (decor.getWidth() > decor.getHeight()) != navLandscape) {
                        android.view.ViewParent p = navTiles.getParent();
                        if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(navTiles);
                        navTiles = null;                    // keep navZoom: the override still stands
                    }
                    attachTiles(decor);
                } else if (navTiles != null) {
                    // Guidance still live but no usable window = picture-in-picture:
                    // take the tiles away without dropping the user's zoom, so it
                    // is still there when the full screen comes back.
                    if (guidance && NAV_ZOOM_BUTTONS) hideTiles();
                    else detachTiles();                     // nav ended, or toggle off
                }
            } catch (Throwable t) { if (NAV_TRACE) android.util.Log.w("UA", "NAVZOOM tick " + t); }
            reschedule();
        }
        private void reschedule() {
            // Fast while guidance is live so a retry (badge not laid out yet)
            // costs ~150ms, not a second; slow when idle so it stays cheap.
            long ms = (android.os.SystemClock.uptimeMillis() - navCamAt) < 3000L ? 150L : 1000L;
            try { new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, ms); } catch (Throwable t) { }
        }
    }

    /** The window to hang the tiles on: prefer one that actually contains the
     *  navigation screen (its speed badge, or nav_container when that id happens
     *  to resolve), else the largest shown window. */
    /** Below this, in dp on the SHORT edge, the window cannot be the navigation
     *  screen. Maps' picture-in-picture window is the case that matters: when you
     *  leave navigation the same Activity window shrinks to a thumbnail, the
     *  camera hook keeps firing, and the tiles were happily drawn inside it at
     *  full size -- a reset button floating over the home screen. Landscape on a
     *  phone is still ~400dp on the short edge; PiP is under 100. */
    static final float MIN_NAV_DP = 200f;

    static android.view.ViewGroup pickDecor(java.util.List<android.view.View> roots) {
        android.view.ViewGroup best = null;
        long bestArea = -1;
        for (android.view.View root : roots) {
            if (!(root instanceof android.view.ViewGroup) || !root.isShown()) continue;
            if (root.getClass().getName().indexOf("Popup") >= 0) continue;
            try {
                float d = root.getResources().getDisplayMetrics().density;
                int shortEdge = Math.min(root.getWidth(), root.getHeight());
                if (shortEdge < MIN_NAV_DP * d) continue;      // picture-in-picture
            } catch (Throwable t) { }
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            if (navContainerId != 0 && findNavContainer(g) != null) return g;
            if (findSpeedBadge(g) != null) return g;
            long area = (long) g.getWidth() * g.getHeight();
            if (area > bestArea) { bestArea = area; best = g; }
        }
        return best;
    }

    private static int navTick;
    private static boolean navTickerStarted;
    static void startNavTicker() {
        if (navTickerStarted) return;
        navTickerStarted = true;
        try { new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new NavTicker(), 1500); }
        catch (Throwable t) { navTickerStarted = false; }
    }

    // ---- black theme: popup menus -----------------------------------------
    //
    // The overflow menu on the directions screen (and any other popup built the
    // same way) came up as a solid black rectangle in Black theme: its items are
    // all there in the view tree -- "Edit stops", "Refresh", "About these
    // results" -- but drawn black on black. With Black OFF the same popup is a
    // white sheet with dark text, so the popup follows a LIGHT theme for its
    // text while its background ends up black under our -mcc9999 variants: the
    // background has a black variant, the text colour does not.
    //
    // Fixing it at the resource layer would mean chasing whichever colour each
    // popup resolves its text from. Reading the colour the app actually rendered
    // is both simpler and self-correcting, and is scoped tightly: only windows
    // whose root is a PopupWindow's decor, and only while Black theme is on.
    /** Diagnostic: logs each label it repaints. Flip on to re-check a popup. */
    static boolean POPUP_TRACE = false;
    private static boolean popupTickerStarted;

    static void startPopupTicker() {
        if (popupTickerStarted || !BLACK) return;
        popupTickerStarted = true;
        try { new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new PopupTicker(), 1200); }
        catch (Throwable t) { popupTickerStarted = false; }
    }

    /** Runs per FRAME, not on a slow timer. At 700 ms the menu visibly drew black
     *  and then flipped to white a moment later -- reported as the options
     *  "rendering intermittently". Checking each frame repaints it on the first
     *  frame it exists, so there is nothing to see.
     *
     *  Cheap enough to do that: when no popup is open the whole tick is iterating
     *  a handful of window roots and one string check; a popup's own tree is a
     *  dozen views. */
    static final class PopupTicker implements Runnable, android.view.Choreographer.FrameCallback {
        public void doFrame(long frameTimeNanos) { run(); }

        public void run() {
            try {
                if (BLACK) {
                    for (android.view.View root : rootViews()) {
                        if (root.getClass().getName().indexOf("Popup") < 0) continue;
                        lightenDarkText(root);
                    }
                }
            } catch (Throwable t) { }
            try { android.view.Choreographer.getInstance().postFrameCallback(this); }
            catch (Throwable t) {
                try { new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 50); }
                catch (Throwable t2) { popupTickerStarted = false; }
            }
        }
    }

    /** Repaint text that would be invisible on our blackened popup background.
     *  Idempotent: once a label is light it no longer matches. */
    static void lightenDarkText(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            try {
                int c = t.getCurrentTextColor();
                int a = (c >>> 24) & 0xff;
                int lum = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
                if (a > 0x40 && lum < 0x60) {
                    if (POPUP_TRACE) android.util.Log.w("UA", "POPUP text #"
                            + Integer.toHexString(c) + " lum=" + lum + " -> light : " + t.getText());
                    t.setTextColor(0xFFE8EAED);
                }
            } catch (Throwable th) { }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) lightenDarkText(g.getChildAt(i));
        }
    }

    public static int color(int c) {
        if (!BLACK) return c;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx >= 0x4c || mx - mn >= 0x0c) return c;
        if (r == CONTAINER && g == CONTAINER && b == CONTAINER) return c;   // already ours
        int v;
        if (mx <= 0x06) v = 0x00;            // true blacks stay black
        else if (mx <= 0x10) v = CONTAINER;  // the row/card container -> visible
        else v = 0x00;                       // ground, surfaces, elevated -> black
        return (c & 0xff000000) | (v << 16) | (v << 8) | v;
    }

    /** For `new ColorStateList(states, colors)` / `new ColorDrawable(c)` call sites: the argument is remapped in place. */
    public static int[] colors(int[] a) {
        if (!BLACK || a == null) return a;
        int[] b = a.clone();
        for (int i = 0; i < b.length; i++) b[i] = color(b[i]);
        return b;
    }
    public static void setTint(Drawable d, int c) { d.setTint(color(c)); }
    public static ColorStateList valueOf(int c) { return ColorStateList.valueOf(color(c)); }
    public static void setColor(GradientDrawable g, int c) { g.setColor(color(c)); }
    public static void setColor(ColorDrawable d, int c) { d.setColor(color(c)); }
    public static void setColor(Paint p, int c) { p.setColor(color(c)); }
    public static void setBackgroundColor(View v, int c) { v.setBackgroundColor(color(c)); }
    public static void setTextColor(TextView t, int c) { t.setTextColor(color(c)); }
    public static void drawColor(Canvas cv, int c) { cv.drawColor(color(c)); }
    public static void setStatusBarColor(Window w, int c) { w.setStatusBarColor(color(c)); }
    public static void setNavigationBarColor(Window w, int c) { w.setNavigationBarColor(color(c)); }

    // ---- Outline (clipToOutline / elevation shadows) ----------------------

    public static void setRoundRect(Outline o, int l, int t, int rt, int b, float radius) { o.setRoundRect(l, t, rt, b, clamp(radius)); }
    public static void setRoundRect(Outline o, Rect rect, float radius) { o.setRoundRect(rect, clamp(radius)); }
    public static void setOval(Outline o, int l, int t, int rt, int b) {
        if (RECT) o.setRoundRect(l, t, rt, b, r()); else o.setOval(l, t, rt, b);
    }
    public static void setOval(Outline o, Rect rect) {
        if (RECT) o.setRoundRect(rect, r()); else o.setOval(rect);
    }
}
