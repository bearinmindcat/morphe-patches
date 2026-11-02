package org.ungoogled.honorweather;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Every Settings.Secure / System / Global read and write in Weather comes here. On MagicOS Weather is a system app that
 * keeps some of its own state in the phone's settings (its card style, the date the privacy statement was agreed, the
 * location type, ...); elsewhere those writes need permissions it does not hold and threw SecurityException at start-up.
 * Its writes now go to its own preferences instead (the phone's settings are never touched), and a read answers what it
 * wrote there, else the phone's own value. Honor's "Service improvement" (analytics) and personalised ads switches read
 * off: off MagicOS neither service exists (see HonorSdksPatch), so the screens no longer show them on.
 */
public final class MagicSettings {
    private static final java.util.Set<String> OFF = new java.util.HashSet<>(java.util.Arrays.asList(
            "weather_statistics_service_state", "state_of_ads_switch"));

    private static SharedPreferences preferences;

    private MagicSettings() {
    }

    private static synchronized SharedPreferences store() {
        if (preferences == null) {
            Context context = application();
            if (context == null) return null;
            preferences = context.createDeviceProtectedStorageContext().getSharedPreferences("magic_settings", Context.MODE_PRIVATE);
        }
        return preferences;
    }

    /** The app's Application (any settings call comes after it exists). */
    static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String local(String table, String name) {
        SharedPreferences p = store();
        return p == null || name == null ? null : p.getString(table + "/" + name, null);
    }

    private static boolean put(String table, String name, String value) {
        SharedPreferences p = store();
        if (p == null || name == null) return false;
        SharedPreferences.Editor e = p.edit();
        if (value == null) e.remove(table + "/" + name); else e.putString(table + "/" + name, value);
        e.apply();
        return true;
    }

    private static String read(String table, ContentResolver r, String name) {
        if (OFF.contains(name)) return "0";
        String value = local(table, name);
        if (value != null) return value;
        try {
            switch (table) {
                case "secure": return Settings.Secure.getString(r, name);
                case "system": return Settings.System.getString(r, name);
                default: return Settings.Global.getString(r, name);
            }
        } catch (SecurityException e) {
            return null;
        }
    }

    private static int toInt(String table, ContentResolver r, String name, Integer def) throws Settings.SettingNotFoundException {
        String v = read(table, r, name);
        try {
            if (v != null) return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            // as Android: an unparsable value is "not found" or the default
        }
        if (def == null) throw new Settings.SettingNotFoundException(name);
        return def;
    }

    private static long toLong(String table, ContentResolver r, String name, Long def) throws Settings.SettingNotFoundException {
        String v = read(table, r, name);
        try {
            if (v != null) return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            // as above
        }
        if (def == null) throw new Settings.SettingNotFoundException(name);
        return def;
    }

    private static float toFloat(String table, ContentResolver r, String name, Float def) throws Settings.SettingNotFoundException {
        String v = read(table, r, name);
        try {
            if (v != null) return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            // as above
        }
        if (def == null) throw new Settings.SettingNotFoundException(name);
        return def;
    }

    // Settings.Secure

    public static String secureGetString(ContentResolver r, String name) { return read("secure", r, name); }
    public static int secureGetInt(ContentResolver r, String name, int def) { try { return toInt("secure", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static int secureGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toInt("secure", r, name, null); }
    public static long secureGetLong(ContentResolver r, String name, long def) { try { return toLong("secure", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static long secureGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toLong("secure", r, name, null); }
    public static float secureGetFloat(ContentResolver r, String name, float def) { try { return toFloat("secure", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static float secureGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toFloat("secure", r, name, null); }
    public static boolean securePutString(ContentResolver r, String name, String value) { return put("secure", name, value); }
    public static boolean securePutInt(ContentResolver r, String name, int value) { return put("secure", name, Integer.toString(value)); }
    public static boolean securePutLong(ContentResolver r, String name, long value) { return put("secure", name, Long.toString(value)); }
    public static boolean securePutFloat(ContentResolver r, String name, float value) { return put("secure", name, Float.toString(value)); }

    // Settings.System

    public static String systemGetString(ContentResolver r, String name) { return read("system", r, name); }
    public static int systemGetInt(ContentResolver r, String name, int def) { try { return toInt("system", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static int systemGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toInt("system", r, name, null); }
    public static long systemGetLong(ContentResolver r, String name, long def) { try { return toLong("system", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static long systemGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toLong("system", r, name, null); }
    public static float systemGetFloat(ContentResolver r, String name, float def) { try { return toFloat("system", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static float systemGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toFloat("system", r, name, null); }
    public static boolean systemPutString(ContentResolver r, String name, String value) { return put("system", name, value); }
    public static boolean systemPutInt(ContentResolver r, String name, int value) { return put("system", name, Integer.toString(value)); }
    public static boolean systemPutLong(ContentResolver r, String name, long value) { return put("system", name, Long.toString(value)); }
    public static boolean systemPutFloat(ContentResolver r, String name, float value) { return put("system", name, Float.toString(value)); }

    // Settings.Global

    public static String globalGetString(ContentResolver r, String name) { return read("global", r, name); }
    public static int globalGetInt(ContentResolver r, String name, int def) { try { return toInt("global", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static int globalGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toInt("global", r, name, null); }
    public static long globalGetLong(ContentResolver r, String name, long def) { try { return toLong("global", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static long globalGetLong(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toLong("global", r, name, null); }
    public static float globalGetFloat(ContentResolver r, String name, float def) { try { return toFloat("global", r, name, def); } catch (Settings.SettingNotFoundException e) { return def; } }
    public static float globalGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException { return toFloat("global", r, name, null); }
    public static boolean globalPutString(ContentResolver r, String name, String value) { return put("global", name, value); }
    public static boolean globalPutInt(ContentResolver r, String name, int value) { return put("global", name, Integer.toString(value)); }
    public static boolean globalPutLong(ContentResolver r, String name, long value) { return put("global", name, Long.toString(value)); }
    public static boolean globalPutFloat(ContentResolver r, String name, float value) { return put("global", name, Float.toString(value)); }
}
