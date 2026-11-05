package org.ungoogled.honorhome;

import android.app.Activity;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Log;

/**
 * Honor Home's home screen styles (standard: every app on the home screens; drawer: an app drawer with its own search)
 * are separate home activities, and MagicOS's Settings app picks one ("Home screen style"). Without it the drawer style
 * could not be chosen: Home screen settings gets a "Drawer Home" switch (the app's own label for that style), which
 * switches styles the way Honor Home itself does (HomeSwitcher.changeHomeTo).
 */
public final class HomeStyle {
    private static final String TAG = "UA";
    /** Honor Home's launcher types (Settings.getLauncherType): standard and drawer. */
    private static final int STANDARD = 1;
    private static final int DRAWER = 4;
    private static final String STANDARD_HOME = "com.hihonor.android.launcher.unihome.UniHomeLauncher";
    private static final String DRAWER_HOME = "com.hihonor.android.launcher.drawer.DrawerLauncher";

    private HomeStyle() {
    }

    private static Object call(String className, String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return Class.forName(className).getMethod(method, types).invoke(null, args);
    }

    private static int currentType() {
        try {
            return (Integer) call("com.hihonor.android.launcher.Settings", "getLauncherType", new Class<?>[0]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return STANDARD;
        }
    }

    /** Called at the end of Home screen settings' onCreate. */
    public static void add(final PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceScreen();
        int title = activity.getResources().getIdentifier("drawerui_lable", "string", activity.getPackageName());
        if (screen == null || title == 0) return;
        final int type = currentType();
        // Simple mode and the other special styles have their own way back.
        if (type != STANDARD && type != DRAWER) return;
        SwitchPreference drawer = new SwitchPreference(activity);
        drawer.setKey("ungoogled_drawer_home");
        drawer.setTitle(title);
        drawer.setPersistent(false);
        drawer.setOrder(-1);
        drawer.setChecked(type == DRAWER);
        drawer.setOnPreferenceChangeListener((preference, value) -> {
            switchTo(activity, Boolean.TRUE.equals(value) ? DRAWER : STANDARD);
            return true;
        });
        screen.addPreference(drawer);
    }

    private static void switchTo(Activity settings, int type) {
        // The new style's home activity is enabled first: Honor Home turns the others off before it turns the new one
        // on, and in between the package has no home activity, so Android took the Home role away from it (and asked
        // for a Home app again).
        try {
            settings.getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(settings, type == DRAWER ? DRAWER_HOME : STANDARD_HOME),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            Log.w(TAG, "home style activity not enabled", e);
            return;
        }
        Activity launcher = null;
        try {
            launcher = (Activity) call("com.hihonor.android.launcher.Launcher", "getLauncher", new Class<?>[0]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The settings screen switches then.
        }
        try {
            call("com.hihonor.android.launcher.HomeSwitcher", "changeHomeTo", new Class<?>[] {Activity.class, int.class},
                    launcher != null ? launcher : settings, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "home style not changed", e);
            return;
        }
        if (launcher != null) settings.finish();
    }
}
