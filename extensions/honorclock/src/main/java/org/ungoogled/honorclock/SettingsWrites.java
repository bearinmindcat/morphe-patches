package org.ungoogled.honorclock;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

/**
 * Honor Clock's writes to Android's settings (patches/.../honorclock sends its Settings.Secure / System put calls here).
 * On an Honor phone Clock is a system app holding WRITE_SECURE_SETTINGS, and keeps its own state there (the Muslim
 * widget switch, the next alarm for MagicOS's lock screen, the fingerprint slide switch while ringing); any other app
 * is refused with a SecurityException, which killed Clock at start. A refused write is skipped, as on a phone that
 * has no such setting: the app's reads then get their defaults.
 */
public final class SettingsWrites {
    private static final String TAG = "HonorClock";

    private SettingsWrites() {
    }

    private static boolean refused(String name, RuntimeException e) {
        Log.i(TAG, "settings write refused: " + name + " (" + e.getClass().getSimpleName() + ")");
        return false;
    }

    public static boolean securePutInt(ContentResolver resolver, String name, int value) {
        try {
            return Settings.Secure.putInt(resolver, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return refused(name, e);
        }
    }

    public static boolean securePutLong(ContentResolver resolver, String name, long value) {
        try {
            return Settings.Secure.putLong(resolver, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return refused(name, e);
        }
    }

    public static boolean securePutString(ContentResolver resolver, String name, String value) {
        try {
            return Settings.Secure.putString(resolver, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return refused(name, e);
        }
    }

    public static boolean systemPutString(ContentResolver resolver, String name, String value) {
        try {
            return Settings.System.putString(resolver, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return refused(name, e);
        }
    }
}
