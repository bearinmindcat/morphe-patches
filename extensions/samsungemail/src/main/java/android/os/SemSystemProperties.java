package android.os;

/**
 * Stand-in for One UI's SemSystemProperties (names and signatures Samsung Email uses, directly and by reflection; no
 * Samsung code): Android's own system properties, where Samsung's (ro.csc.*, persist.omc.*) are empty. Email reads
 * get, getBoolean, getInt and getLong by reflection and uses the answer unchecked, so each one is here.
 */
public class SemSystemProperties {
    private static String real(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : (String) value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    public static String get(String key) {
        return real(key);
    }

    public static String get(String key, String def) {
        String value = real(key);
        return value.isEmpty() ? def : value;
    }

    public static boolean getBoolean(String key, boolean def) {
        String value = real(key);
        if (value.isEmpty()) return def;
        if ("1".equals(value) || "true".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) return true;
        if ("0".equals(value) || "false".equalsIgnoreCase(value) || "n".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)) return false;
        return def;
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(real(key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        try {
            return Long.parseLong(real(key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String getSalesCode() {
        return "";
    }

    public static String getCountryIso() {
        return "";
    }

    public static String getCountryCode() {
        return "";
    }
}
