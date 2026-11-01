package org.ungoogled.xiaominotes;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * The phone's system properties as Xiaomi Notes reads them (android.os.SystemProperties and MiCloud's wrapper of it), with
 * the answers of a Xiaomi Global build where the phone has none: off HyperOS miuix decides "international" from
 * ro.product.mod_device ending in "_global" and the apps read ro.miui.region (default "CN") and ro.miui.build.region
 * (default "cn"), so without these a Global APK behaves like China's build (consent screens, offline features off).
 * The patch sends the app's property reads here (see redirectMiuiProperties); a value the phone has always wins.
 */
public final class MiuiProps {
    private static Method get;

    private MiuiProps() {
    }

    /** What a Global build answers for Xiaomi's own properties, or null. */
    private static String global(String key) {
        switch (key) {
            case "ro.product.mod_device":
                return android.os.Build.DEVICE + "_global";
            case "ro.miui.region": {
                String country = Locale.getDefault().getCountry();
                return country.isEmpty() ? "GL" : country.toUpperCase(Locale.ROOT);
            }
            case "ro.miui.build.region":
                return "global";
        }
        return null;
    }

    /** The phone's own value, "" where it has none. */
    private static String phone(String key) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, "");
            return value == null ? "" : (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String def) {
        String value = key == null ? "" : phone(key);
        if (!value.isEmpty()) return value;
        String global = key == null ? null : global(key);
        return global != null ? global : def;
    }

    public static int getInt(String key, int def) {
        try {
            String value = get(key, null);
            return value == null || value.isEmpty() ? def : Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        try {
            String value = get(key, null);
            return value == null || value.isEmpty() ? def : Long.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** As android.os.SystemProperties: y/yes/1/true/on and n/no/0/false/off, else the default. */
    public static boolean getBoolean(String key, boolean def) {
        String value = get(key, "");
        switch (value) {
            case "1": case "y": case "yes": case "true": case "on":
                return true;
            case "0": case "n": case "no": case "false": case "off":
                return false;
        }
        return def;
    }
}
