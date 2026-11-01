package org.ungoogled.APP;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Android's system settings as APP_NAME uses them: on LG's ROM the app is a system app and keeps some of its own state
 * there ("isQmemoGifCapturing", tips flags); a re-signed copy may not write system settings (SecurityException), so a
 * value it cannot write is kept in its own preferences, and reading a key answers that value first. Settings it only
 * reads go to Android as before. The patch sends the app's Settings.System putInt/getInt calls here.
 */
public final class LgSettings {
    private static final String FILE = "lg_system_settings";

    private LgSettings() {
    }

    private static SharedPreferences local() {
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return c == null ? null : c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static boolean putInt(ContentResolver resolver, String key, int value) {
        try {
            return Settings.System.putInt(resolver, key, value);
        } catch (SecurityException | IllegalArgumentException e) {
            SharedPreferences prefs = local();
            if (prefs == null) return false;
            prefs.edit().putInt(key, value).apply();
            return true;
        }
    }

    public static int getInt(ContentResolver resolver, String key, int fallback) {
        SharedPreferences prefs = local();
        if (prefs != null && prefs.contains(key)) return prefs.getInt(key, fallback);
        return Settings.System.getInt(resolver, key, fallback);
    }
}
