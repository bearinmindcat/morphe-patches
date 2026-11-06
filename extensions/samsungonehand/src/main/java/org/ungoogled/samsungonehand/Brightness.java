package org.ungoogled.samsungonehand;

import android.content.Context;
import android.provider.Settings;

/**
 * The quick tools' brightness slider. One UI lets the app read and set the display's brightness through hidden
 * DisplayManager calls (by reflection), which Android refuses an app. Here those calls read and write the brightness
 * setting, which an app may change once the user allows it to modify system settings.
 */
public final class Brightness {
    private static final float MAX = 255f;

    private Brightness() {
    }

    /** The app's reflective DisplayManager call: [semG|g]etBrightness, [sem]setBrightness, [sem]setTemporaryBrightness. */
    public static Object call(Object helper, Object displayManager, String name, Class<?>[] types, Object[] args) {
        Context c = OneHand.context();
        if (c == null || name == null) return null;
        try {
            switch (name) {
                case "getBrightness":
                case "semGetBrightness":
                    return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / MAX;
                case "setTemporaryBrightness":
                    // (level 0..255, boolean): while the slider moves, only when already allowed.
                    if (Settings.System.canWrite(c)) set(c, (Integer) args[0]);
                    return null;
                case "semSetTemporaryBrightness":
                    if (Settings.System.canWrite(c)) set(c, Math.round((Float) args[0] * MAX));
                    return null;
                case "setBrightness":
                case "semSetBrightness":
                    // (display, 0..1): the slider let go.
                    if (Actions.canWriteSettings(c)) set(c, Math.round((Float) args[1] * MAX));
                    return null;
            }
        } catch (ClassCastException | ArrayIndexOutOfBoundsException | NullPointerException | SecurityException e) {
            OneHand.debug("brightness: " + e);
        }
        return null;
    }

    private static void set(Context c, int level) {
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(255, level)));
    }
}
