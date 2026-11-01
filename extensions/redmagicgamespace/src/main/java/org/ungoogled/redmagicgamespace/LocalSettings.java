package org.ungoogled.redmagicgamespace;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * Every Settings.Global / Secure / System read and write in RedMagic Game Space comes here. On RedMagic OS the app is a system
 * app and keeps its state (game mode, panel switches, per-game options) in Android's settings tables, where the rest
 * of RedMagic OS reads it. An ordinary app may not write Global or Secure (WRITE_SECURE_SETTINGS, adb only) nor
 * RedMagic's own keys in System, and the refused write threw SecurityException at start-up. A refused write is kept in
 * the app's own preferences instead and announced to the app's observers of that setting (as Android announces a
 * real write); reads see it, so the app finds its own state again. Writes the phone allows (e.g. after "pm grant ...
 * WRITE_SECURE_SETTINGS") go to Android as before, and a kept value of that key is dropped.
 */
public final class LocalSettings {
    private static final String TAG = "UA";
    private static final String PREFS = "redmagic_local_settings";
    private static SharedPreferences prefs;

    private LocalSettings() {
    }

    private static synchronized SharedPreferences prefs() {
        if (prefs != null) return prefs;
        Context app = null;
        try {
            app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "no application for local settings", e);
        }
        if (app != null) prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs;
    }

    private static String id(String table, String name) {
        return table + "/" + name;
    }

    private static Uri uri(String table, String name) {
        switch (table) {
            case "global": return Settings.Global.getUriFor(name);
            case "secure": return Settings.Secure.getUriFor(name);
            default: return Settings.System.getUriFor(name);
        }
    }

    private static String real(String table, ContentResolver r, String name) {
        try {
            switch (table) {
                case "global": return Settings.Global.getString(r, name);
                case "secure": return Settings.Secure.getString(r, name);
                default: return Settings.System.getString(r, name);
            }
        } catch (SecurityException e) {
            return null; // Android's hidden settings
        }
    }

    private static String read(String table, ContentResolver r, String name) {
        SharedPreferences p = prefs();
        String key = id(table, name);
        if (p != null && p.contains(key)) return p.getString(key, null);
        return real(table, r, name);
    }

    private static boolean write(String table, ContentResolver r, String name, String value) {
        try {
            boolean ok;
            switch (table) {
                case "global": ok = Settings.Global.putString(r, name, value); break;
                case "secure": ok = Settings.Secure.putString(r, name, value); break;
                default: ok = Settings.System.putString(r, name, value); break;
            }
            if (ok) {
                SharedPreferences p = prefs();
                if (p != null && p.contains(id(table, name))) p.edit().remove(id(table, name)).apply();
            }
            return ok;
        } catch (SecurityException | IllegalArgumentException e) {
            // Not allowed for an ordinary app: kept locally.
        }
        SharedPreferences p = prefs();
        if (p == null) return false;
        String key = id(table, name);
        String old = p.getString(key, null);
        p.edit().putString(key, value).apply();
        if (old == null ? value != null : !old.equals(value)) {
            try {
                r.notifyChange(uri(table, name), null);
            } catch (RuntimeException e) {
                Log.w(TAG, "local setting " + key + " not announced", e);
            }
        }
        return true;
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return v == null ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float parseFloat(String v, float def) {
        try {
            return v == null ? def : Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intOrThrow(String table, ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Integer.parseInt(read(table, r, name));
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    private static long longOrThrow(String table, ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Long.parseLong(read(table, r, name));
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    private static float floatOrThrow(String table, ContentResolver r, String name) throws Settings.SettingNotFoundException {
        String v = read(table, r, name);
        if (v == null) throw new Settings.SettingNotFoundException(name);
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    // Settings.Global

    public static String globalGetString(ContentResolver r, String name) { return read("global", r, name); }
    public static int globalGetInt(ContentResolver r, String name, int def) { return parseInt(read("global", r, name), def); }
    public static int globalGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return intOrThrow("global", r, name); }
    public static long globalGetLong(ContentResolver r, String name, long def) { return parseLong(read("global", r, name), def); }
    public static long globalGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return longOrThrow("global", r, name); }
    public static float globalGetFloat(ContentResolver r, String name, float def) { return parseFloat(read("global", r, name), def); }
    public static float globalGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return floatOrThrow("global", r, name); }
    public static boolean globalPutString(ContentResolver r, String name, String value) { return write("global", r, name, value); }
    public static boolean globalPutInt(ContentResolver r, String name, int value) { return write("global", r, name, Integer.toString(value)); }
    public static boolean globalPutLong(ContentResolver r, String name, long value) { return write("global", r, name, Long.toString(value)); }
    public static boolean globalPutFloat(ContentResolver r, String name, float value) { return write("global", r, name, Float.toString(value)); }

    // Settings.Secure

    public static String secureGetString(ContentResolver r, String name) { return read("secure", r, name); }
    public static int secureGetInt(ContentResolver r, String name, int def) { return parseInt(read("secure", r, name), def); }
    public static int secureGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return intOrThrow("secure", r, name); }
    public static long secureGetLong(ContentResolver r, String name, long def) { return parseLong(read("secure", r, name), def); }
    public static long secureGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return longOrThrow("secure", r, name); }
    public static float secureGetFloat(ContentResolver r, String name, float def) { return parseFloat(read("secure", r, name), def); }
    public static float secureGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return floatOrThrow("secure", r, name); }
    public static boolean securePutString(ContentResolver r, String name, String value) { return write("secure", r, name, value); }
    public static boolean securePutInt(ContentResolver r, String name, int value) { return write("secure", r, name, Integer.toString(value)); }
    public static boolean securePutLong(ContentResolver r, String name, long value) { return write("secure", r, name, Long.toString(value)); }
    public static boolean securePutFloat(ContentResolver r, String name, float value) { return write("secure", r, name, Float.toString(value)); }

    // Settings.System

    public static String systemGetString(ContentResolver r, String name) { return read("system", r, name); }
    public static int systemGetInt(ContentResolver r, String name, int def) { return parseInt(read("system", r, name), def); }
    public static int systemGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return intOrThrow("system", r, name); }
    public static long systemGetLong(ContentResolver r, String name, long def) { return parseLong(read("system", r, name), def); }
    public static long systemGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return longOrThrow("system", r, name); }
    public static float systemGetFloat(ContentResolver r, String name, float def) { return parseFloat(read("system", r, name), def); }
    public static float systemGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return floatOrThrow("system", r, name); }
    public static boolean systemPutString(ContentResolver r, String name, String value) { return write("system", r, name, value); }
    public static boolean systemPutInt(ContentResolver r, String name, int value) { return write("system", r, name, Integer.toString(value)); }
    public static boolean systemPutLong(ContentResolver r, String name, long value) { return write("system", r, name, Long.toString(value)); }
    public static boolean systemPutFloat(ContentResolver r, String name, float value) { return write("system", r, name, Float.toString(value)); }
    /** The hidden per-user write: the app's own user is the only one an ordinary app may write for. */
    public static boolean systemPutStringForUser(ContentResolver r, String name, String value, int user) { return write("system", r, name, value); }
}
