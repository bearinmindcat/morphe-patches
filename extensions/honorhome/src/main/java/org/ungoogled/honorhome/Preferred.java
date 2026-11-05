package org.ungoogled.honorhome;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

/**
 * Preferred activities. When it switches home screen styles, Honor Home (a system app on MagicOS) makes the new style's
 * home activity the preferred Home, which needs SET_PREFERRED_APPLICATIONS. An installed copy is the Home app through
 * Android's Home role instead, and Android picks whichever of its home activities is enabled, so the call is skipped
 * where Android refuses it (it threw, and the style did not change). Its Home intent then would bring up Android's
 * Home chooser (the preferred Home was the old style's activity), so that one start goes to the new activity directly.
 */
public final class Preferred {
    /** The home activity Honor Home last tried to make the preferred one. */
    private static volatile ComponentName home;

    private Preferred() {
    }

    /** Activity.startActivity in Honor Home's style switch: its Home intent goes to the new style's activity. */
    public static void startActivity(android.app.Activity activity, android.content.Intent intent) {
        ComponentName target = home;
        if (target != null && intent.getComponent() == null && intent.hasCategory(android.content.Intent.CATEGORY_HOME)
                && target.getPackageName().equals(activity.getPackageName())) {
            intent.setComponent(target);
        }
        activity.startActivity(intent);
    }

    public static void clearPackagePreferredActivities(PackageManager pm, String packageName) {
        try {
            pm.clearPackagePreferredActivities(packageName);
        } catch (SecurityException e) {
            // Not allowed for an installed app.
        }
    }

    public static void addPreferredActivity(PackageManager pm, IntentFilter filter, int match, ComponentName[] set,
            ComponentName activity) {
        home = activity;
        try {
            pm.addPreferredActivity(filter, match, set, activity);
        } catch (SecurityException e) {
            // Not allowed for an installed app.
        }
    }
}
