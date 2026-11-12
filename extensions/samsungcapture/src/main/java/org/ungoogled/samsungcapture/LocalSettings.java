package org.ungoogled.samsungcapture;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Every Settings.Global / Secure / System read and write in Samsung capture comes here. On One UI it is a system app
 * and keeps its own options in Android's settings tables (screen recorder sound, quality, selfie view size, screenshot
 * toolbar, format, ...), which One UI's SystemUI reads for its screenshots. This copy keeps every option it writes in
 * its own files instead and announces it to the app's observers of that setting (as Android announces a real write);
 * reads see it, so the app finds its own options again, in each of its processes (the settings screen and the
 * recorder run apart). Off One UI Android refuses those writes anyway (WRITE_SECURE_SETTINGS, One UI's keys in System);
 * One UI lets an app write its keys in System, so a copy installed next to Samsung's (Change package name) changed
 * Samsung's own screenshot options. Only a copy installed as the system app (a root mount over Samsung's) still writes
 * to Android, as Samsung's does; a write Android refuses it is kept here too.
 *
 * One file per setting, replaced in one step: shared preferences across processes lost writes (a process re-reading
 * after the announcement put the previous file back while the writer was replacing it).
 */
public final class LocalSettings {
    private static final String TAG = "UA";
    /** Where these settings were kept before (shared preferences, "table/name" keys), moved to files on first use. */
    private static final String OLD_PREFS = "smartcapture_local_settings";
    private static final String DIR = "local_settings";
    private static Context app;
    private static Boolean systemCopy;
    private static File dir;

    private LocalSettings() {
    }

    static synchronized Context context() {
        if (app == null) {
            try {
                app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.w(TAG, "no application for local settings", e);
            }
            if (app == null) app = Services.app();
        }
        return app;
    }

    /** Installed as the phone's system app (a root mount over Samsung's own copy), not next to it or on another ROM. */
    static synchronized boolean systemCopy() {
        if (systemCopy == null) {
            Context c = context();
            if (c == null) return false;
            systemCopy = (c.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        return systemCopy;
    }

    private static synchronized File dir() {
        if (dir != null) return dir;
        Context c = context();
        if (c == null) return null;
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) {
            File staging = new File(c.getFilesDir(), DIR + "." + Process.myPid());
            staging.mkdirs();
            try {
                SharedPreferences old = c.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
                for (Map.Entry<String, ?> e : old.getAll().entrySet()) {
                    int slash = e.getKey().indexOf('/');
                    if (slash > 0 && e.getValue() instanceof String) {
                        store(new File(staging, fileName(e.getKey().substring(0, slash), e.getKey().substring(slash + 1))),
                            (String) e.getValue());
                    }
                }
            } catch (RuntimeException | IOException e) {
                Log.w(TAG, "local settings not moved", e);
            }
            // Another process may have moved them first: then its directory stays.
            if (!staging.renameTo(d)) {
                for (File f : files(staging)) f.delete();
                staging.delete();
            } else {
                c.deleteSharedPreferences(OLD_PREFS);
            }
        }
        dir = d;
        return d;
    }

    private static File[] files(File d) {
        File[] list = d.listFiles();
        return list == null ? new File[0] : list;
    }

    /** One setting's file. */
    private static File file(String table, String name) {
        File d = dir();
        return d == null ? null : new File(d, fileName(table, name));
    }

    /** Setting names are letters, digits, '_' and '.'; anything else is escaped. */
    private static String fileName(String table, String name) {
        StringBuilder b = new StringBuilder(table).append('-');
        for (byte x : name.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (x & 0xff);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.') {
                b.append(ch);
            } else {
                b.append('%').append(String.format("%02x", x & 0xff));
            }
        }
        return b.toString();
    }

    /** Writes [value] next to [f] and renames it over [f], so readers see the old or the new value, never a mix. */
    private static void store(File f, String value) throws IOException {
        File tmp = new File(f.getParentFile(), "." + f.getName() + "." + Process.myPid() + "." + Thread.currentThread().getId());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename to " + f);
        }
    }

    /** The kept value; null when nothing is kept (the setting then comes from Android). */
    private static String local(File f) {
        if (f == null) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            Log.w(TAG, "local setting " + f.getName() + " not read", e);
            return null;
        }
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
        String kept = local(file(table, name));
        return kept != null ? kept : real(table, r, name);
    }

    private static boolean write(String table, ContentResolver r, String name, String value) {
        File f = file(table, name);
        if (systemCopy()) {
            try {
                boolean ok;
                switch (table) {
                    case "global": ok = Settings.Global.putString(r, name, value); break;
                    case "secure": ok = Settings.Secure.putString(r, name, value); break;
                    default: ok = Settings.System.putString(r, name, value); break;
                }
                if (ok && f != null) f.delete();
                return ok;
            } catch (SecurityException | IllegalArgumentException e) {
                // Refused: kept locally.
            }
        }
        if (f == null) return false;
        String old = local(f);
        // An unchanged value is not written again (the settings screen echoes every change it is told about).
        if (old == null ? value == null : old.equals(value)) return true;
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "local setting " + f.getName() + " = " + value + " (was " + old + ")");
        if (value == null) {
            // Nothing kept: Android's value again, as a null in shared preferences was.
            f.delete();
        } else {
            try {
                store(f, value);
            } catch (IOException e) {
                Log.w(TAG, "local setting " + f.getName() + " not kept", e);
                return false;
            }
        }
        try {
            r.notifyChange(uri(table, name), null);
        } catch (RuntimeException e) {
            Log.w(TAG, "local setting " + f.getName() + " not announced", e);
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
