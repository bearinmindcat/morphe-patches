package org.ungoogled.samsungweather;

import android.content.ComponentName;
import android.content.pm.PackageManager;

/**
 * Weather's launcher icon starts enabled off One UI (the manifest says so), but Weather reads only an explicit
 * "enabled" as on: its "Show Weather on Apps screen" switch showed off while the icon was there. The default state
 * of that one component reads as enabled.
 */
public final class Launcher {
    private static final String LAUNCHER = "com.samsung.android.weather.app.AppLauncherActivity";

    private Launcher() {
    }

    public static int getComponentEnabledSetting(PackageManager pm, ComponentName component) {
        int state = pm.getComponentEnabledSetting(component);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && component != null
                && LAUNCHER.equals(component.getClassName())) {
            return PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        }
        return state;
    }
}
