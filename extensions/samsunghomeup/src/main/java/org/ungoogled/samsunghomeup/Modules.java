package org.ungoogled.samsunghomeup;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

/**
 * Home Up's main screen off One UI. Four of its modules change parts of One UI itself, not One UI Home's home screen:
 * Task changer and Gesture settings change the recents screen and navigation gestures, which Android gives only to
 * the phone's own recents app (config_recentsComponentName, One UI Home on a Samsung phone, the phone's own launcher
 * elsewhere); Edge panel changes One UI's edge panel service; Share Manager changes One UI's share sheet. Elsewhere
 * nothing reads what they set, so their tiles are taken off the main screen. Home screen and Back up and restore stay.
 * On One UI nothing changes.
 */
public final class Modules {
    private static final String TAG = "UA";
    private static final String ONE_UI = "com.samsung.feature.samsung_experience_mobile";
    private static final String ONE_UI_HOME = "com.sec.android.app.launcher";
    private static final String EDGE_PANEL_SERVICE = "com.samsung.android.app.cocktailbarservice";

    private Modules() {
    }

    private static boolean oneUi(Context c) {
        return c.getPackageManager().hasSystemFeature(ONE_UI);
    }

    /** Whether One UI Home is the phone's recents app, which also plays the navigation gestures. */
    private static boolean recentsInOneUiHome() {
        try {
            Resources system = Resources.getSystem();
            int id = system.getIdentifier("config_recentsComponentName", "string", "android");
            return id != 0 && system.getString(id).startsWith(ONE_UI_HOME + "/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Whether this phone has what a module changes: "taskchanger", "gesture", "edgepanel" or "sharemanager". */
    static boolean works(Context c, String module) {
        if (oneUi(c)) return true;
        switch (module) {
            case "taskchanger":
            case "gesture":
                return recentsInOneUiHome();
            case "edgepanel":
                return installed(c, EDGE_PANEL_SERVICE);
            default:
                return false;
        }
    }

    /** End of the main screen's set-up (after its listeners): the tiles of modules this phone lacks are removed. */
    public static void mainScreen(Activity activity) {
        try {
            hide(activity, "taskchanger_menu", "taskchanger");
            hide(activity, "gesture_menu", "gesture");
            hide(activity, "edge_panel_menu", "edgepanel");
            hide(activity, "sharemanager_menu", "sharemanager");
        } catch (RuntimeException e) {
            Log.w(TAG, "home up: could not hide the One UI modules", e);
        }
    }

    private static void hide(Activity activity, String viewId, String module) {
        if (works(activity, module)) return;
        int id = activity.getResources().getIdentifier(viewId, "id", activity.getPackageName());
        View tile = id == 0 ? null : activity.findViewById(id);
        if (tile == null) {
            Log.w(TAG, "home up: no tile " + viewId);
            return;
        }
        if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).removeView(tile);
    }

    /**
     * Start of the Edge panel and Share Manager screens' onCreate, which other apps can open: where the phone lacks
     * the module they say so and close instead of crashing (no edge panel settings, no Quick Share app).
     */
    public static boolean closes(Activity activity, String module) {
        if (works(activity, module)) return false;
        String name = "edgepanel".equals(module) ? "Edge panel" : "Share Manager";
        Toast.makeText(activity, name + " needs One UI", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** EdgePanelUtils.isAppsEdgeEnabled: the edge panel list setting is missing off One UI (it crashed on null). */
    public static boolean appsEdgeEnabled(Context c) {
        String panels = Settings.System.getString(c.getContentResolver(), "cocktail_bar_enabled_cocktails");
        return panels != null && panels.contains("AppsEdgePanelProvider");
    }
}
