package org.ungoogled.coloroslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;

/**
 * The launcher's settings link to ColorOS Settings pages ("System navigation" under "You might be looking for", the
 * taskbar settings' navigation entry). Elsewhere nothing opens them and the tap did nothing. A link that does not open
 * on this phone goes to Android's own page for the same thing, or to Settings.
 */
public final class Links {
    private static final String TAG = "UA";
    private static final String COLOROS_NAVIGATION = "oplus.intent.action.NAVIGATION_BAR_SETTINGS";

    /**
     * Where the navigation mode is chosen, in order: One UI's Navigation bar page; AOSP's System page (System
     * navigation is under Gestures there: Android has no page of its own for it that an app may open, so the System
     * page comes with a hint saying where to go next).
     */
    private static final String[][] NAVIGATION_PAGES = {
            {"com.android.settings", "com.android.settings.Settings$NavigationBarSettingsActivity"},
            {"com.android.settings", "com.android.settings.Settings$SystemNavigationGestureSettingsActivity"},
            {"com.android.settings", "com.android.settings.Settings$SystemDashboardActivity"},
    };

    private Links() {
    }

    private static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether an app may start the intent here: an activity takes it and is open to other apps (or is the launcher's). */
    private static boolean opens(Context c, Intent intent) {
        try {
            ResolveInfo info = c.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return false;
            return info.activityInfo.exported || c.getPackageName().equals(info.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Before the launcher's settings start an intent: a ColorOS page this phone lacks becomes Android's own. */
    public static void settings(Intent intent) {
        if (intent == null) return;
        Context c = app();
        if (c == null) return;
        if (opens(c, intent)) return;
        ComponentName component = intent.getComponent();
        boolean navigation = COLOROS_NAVIGATION.equals(intent.getAction())
                || (component != null && component.getClassName().endsWith("NavigationBarSettingsActivity"));
        if (!navigation) return;
        for (String[] page : NAVIGATION_PAGES) {
            Intent android = new Intent(Intent.ACTION_MAIN).setClassName(page[0], page[1]);
            if (opens(c, android)) {
                redirect(intent, android);
                if (page[1].endsWith("$SystemDashboardActivity")) hint(c);
                return;
            }
        }
        redirect(intent, new Intent(Settings.ACTION_SETTINGS));
    }

    /** "Gestures > System navigation", in Settings' own words where it has them. */
    private static void hint(Context c) {
        String gestures = settingsString(c, "gesture_preference_title", "Gestures");
        String navigation = settingsString(c, "system_navigation_title", "System navigation");
        final String text = gestures + " \u203a " + navigation;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show());
    }

    private static String settingsString(Context c, String name, String fallback) {
        try {
            android.content.res.Resources r = c.getPackageManager().getResourcesForApplication("com.android.settings");
            int id = r.getIdentifier(name, "string", "com.android.settings");
            if (id != 0) return r.getString(id);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // English then
        }
        return fallback;
    }

    private static void redirect(Intent intent, Intent to) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "settings link " + intent + " -> " + to);
        intent.setAction(to.getAction());
        intent.setComponent(to.getComponent());
        intent.setPackage(null);
        intent.setData(null);
    }
}
