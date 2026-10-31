package org.ungoogled.samsungmessages;

import android.content.Context;
import android.os.PowerManager;

/**
 * On One UI Messages asks the device idle controller (a hidden system interface) whether it is exempt from battery
 * optimisation and adds itself if not; elsewhere that interface differs and the first call crashed the app at start.
 * Android's public answer stands in; adding itself is left to the user.
 */
public final class Power {
    private Power() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
