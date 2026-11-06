package org.ungoogled.samsungonehand;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

/** What the port's parts share: the app's Context, its own settings, the main thread, a short message. */
public final class OneHand {
    static final String TAG = "OneHand";
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One Hand Operation +'s own settings file and on/off key (its p7 helper, MODE_MULTI_PROCESS). */
    private static final String PREFS = "sidegesturepad_prefs";
    private static final String ENABLED = "sidegesturepad_enabled";
    private static final String PACKAGE = "com.samsung.android.sidegesturepad";
    private static final String SERVICE = PACKAGE + ".SGPService";

    private static Context app;
    private static long lastToast;
    private static String lastText;

    private OneHand() {
    }

    static void debug(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message);
    }

    static synchronized Context context() {
        if (app == null) {
            try {
                Object application = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (application instanceof Context) app = ((Context) application).getApplicationContext();
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Set from the accessibility service or an activity later.
            }
        }
        return app;
    }

    static synchronized void context(Context c) {
        if (app == null && c != null) app = c.getApplicationContext();
    }

    /**
     * Context.getSystemService(Class) for the app: One UI's status bar manager has no service off One UI, so the
     * app gets the stand-in, which asks the accessibility service (see android.app.SemStatusBarManager).
     */
    public static Object service(Context c, Class<?> type) {
        Object service = c.getSystemService(type);
        if (service == null && type == android.app.SemStatusBarManager.class) return new android.app.SemStatusBarManager();
        return service;
    }

    /**
     * Settings writes: One UI lets the app change global and secure settings (it holds WRITE_SECURE_SETTINGS there);
     * elsewhere such a write is refused, and the app does not expect that. A refused write is skipped.
     */
    public static boolean putGlobal(android.content.ContentResolver cr, String name, int value) {
        try {
            return Settings.Global.putInt(cr, name, value);
        } catch (SecurityException e) {
            debug("global setting not changed: " + name);
            return false;
        }
    }

    public static boolean putSecure(android.content.ContentResolver cr, String name, int value) {
        try {
            return Settings.Secure.putInt(cr, name, value);
        } catch (SecurityException e) {
            debug("secure setting not changed: " + name);
            return false;
        }
    }

    public static boolean putSystem(android.content.ContentResolver cr, String name, int value) {
        try {
            return Settings.System.putInt(cr, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            debug("system setting not changed: " + name);
            return false;
        }
    }

    /** One UI SystemUI colours the quick tools take, and the app's own colour for each where SystemUI has none. */
    private static final java.util.Map<String, String> PANEL_COLORS = new java.util.HashMap<>();

    static {
        PANEL_COLORS.put("qs_tile_round_background_off", "colorControlPanelOffBG");
        PANEL_COLORS.put("qs_tile_round_background_on", "colorPrimary");
        PANEL_COLORS.put("qs_tile_icon_off_tint_color", "colorControlPanelIconColor");
        PANEL_COLORS.put("qs_tile_icon_on_dim_tint_color", "colorControlPanelIconColor");
        PANEL_COLORS.put("tw_progress_color_control_activated", "colorPrimary");
    }

    /**
     * The app's "SystemUI colour by name" lookup: One UI's SystemUI has these colours, Android's does not (the quick
     * tools came out white on white). SystemUI's own colour where it has one, else the app's matching colour.
     */
    public static int systemUiColor(Context c, String name) {
        try {
            android.content.res.Resources ui = c.getPackageManager().getResourcesForApplication("com.android.systemui");
            int id = ui.getIdentifier(name, "color", "com.android.systemui");
            if (id != 0) return ui.getColor(id, null);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            // No SystemUI resources to read.
        }
        String own = PANEL_COLORS.get(name);
        if (own == null) return 0;
        int id = c.getResources().getIdentifier(own, "color", c.getPackageName());
        return id == 0 ? 0 : c.getColor(id);
    }

    /** Whether the app's own switch ("One Hand Operation +" on its main screen) is on. */
    @SuppressWarnings("deprecation")
    static boolean switchedOn(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
        return prefs.getBoolean(ENABLED, false);
    }

    /** Has the app's gesture service rebuild its windows (its own "restart"), when its switch is on. */
    static void restartGestures(Context c) {
        if (c == null || !switchedOn(c)) return;
        try {
            c.startService(new Intent("com.samsung.action.SIDEGESTUREPAD_SERVICE")
                    .setComponent(new ComponentName(PACKAGE, SERVICE)).putExtra("option", "restart"));
        } catch (RuntimeException e) {
            Log.w(TAG, "gesture service not restarted: " + e);
        }
    }

    /** Whether the accessibility service is turned on in Android's settings (it may not be connected yet). */
    static boolean serviceEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName own = new ComponentName(c, ActionService.class);
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(enabled);
        for (String entry : split) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (own.equals(name)) return true;
        }
        return false;
    }

    /** A short message, not repeated within two seconds. */
    static void toast(String text) {
        MAIN.post(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (text.equals(lastText) && now - lastToast < 2000) return;
            lastText = text;
            lastToast = now;
            Context c = context();
            if (c != null) Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
        });
    }
}
