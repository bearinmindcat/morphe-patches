package org.ungoogled.honorvideoplayer;

import java.lang.reflect.Method;

/**
 * The phone's system properties as Honor Video Player reads them through MagicOS's SystemPropertiesEx (its stand-in calls
 * these): Android's own SystemProperties, by reflection. Off MagicOS the MagicOS-only properties (msc.*,
 * ro.build.version.magic, ...) are empty, so the app takes the default it passes in, as on a phone without them.
 */
public final class Magic {
    private static Method get;

    private Magic() {
    }

    /** The phone's own value, or def where it has none. */
    public static String prop(String key, String def) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = key == null ? null : get.invoke(null, key, "");
            return value == null || ((String) value).isEmpty() ? def : (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return def;
        }
    }

    public static int propInt(String key, int def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long propLong(String key, long def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Long.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** As android.os.SystemProperties: y/yes/1/true/on and n/no/0/false/off, else the default. */
    public static boolean propBoolean(String key, boolean def) {
        String value = prop(key, "");
        switch (value) {
            case "1": case "y": case "yes": case "true": case "on":
                return true;
            case "0": case "n": case "no": case "false": case "off":
                return false;
        }
        return def;
    }
}
