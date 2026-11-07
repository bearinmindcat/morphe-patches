package org.ungoogled.coloroslauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * The home screen's edit bar sends Wallpapers, Icons and (16.4.30) Personalization to ColorOS's own apps (OPPO's
 * wallpaper and theme editor, com.oplus.wallpapers, and its icon style app, com.oplus.uxdesign); where they are not
 * installed the buttons did nothing. Wallpapers then opens Android's own wallpaper pickers; Icons and Personalization,
 * which have no counterpart outside ColorOS's theme engine, are left out of the bar.
 */
public final class Personalization {
    private static final String TAG = "UA";
    private static final String ICON_STYLE_APP = "com.oplus.uxdesign";
    private static final String THEME_APP = "com.oplus.wallpapers";

    private Personalization() {
    }

    private static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean installed(Context c, String pkg) {
        if (c == null || pkg == null) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** The package an intent is sent to: itself where it is installed, otherwise none (Android picks the app). */
    public static String ifInstalled(String pkg) {
        if (installed(app(), pkg)) return pkg;
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, pkg + " not installed: Android's own app instead");
        return null;
    }

    /** ToggleBarMainUIController.isIgnoreItem's answer, and Icons / Personalization without their ColorOS app. */
    public static boolean ignore(boolean ignored, int viewId, Context c) {
        return ignored || hideToggleItem(c, viewId);
    }

    /** Whether an edit bar button is left out: Icons / Personalization without the ColorOS app they open. */
    static boolean hideToggleItem(Context c, int viewId) {
        if (c == null) return false;
        String name;
        try {
            name = c.getResources().getResourceEntryName(viewId);
        } catch (RuntimeException e) {
            return false;
        }
        switch (name) {
            case "toggle_item_icon_style": return !installed(c, ICON_STYLE_APP);
            case "toggle_item_personalization": return !installed(c, THEME_APP);
            default: return false;
        }
    }
}
