package android.provider;

import org.ungoogled.xiaomilauncher.LocalSettings;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides, hand-written): HyperOS's settings helpers the HyperOS launcher
 * uses. The launcher keeps its own home options in Android's System table (auto-fill empty cells, lock the layout,
 * no-label mode, the minus-one screen, ...), which an installed app may not write: LocalSettings keeps refused writes in
 * the app's own preferences and announces them on Android's URI for the key, so options stick. Booleans are stored as
 * 0/1 like HyperOS does. Cloud data (Xiaomi's server-pushed flags) is never there: the caller's default. Names and
 * signatures only, no Xiaomi code.
 */
public class MiuiSettings {
    public static class ForceTouch {
        public static boolean isEnabled(android.content.Context context) {
            return false;
        }
    }

    public static class Global {
        public static boolean getBoolean(android.content.ContentResolver r, String name) {
            return LocalSettings.globalGetInt(r, name, 0) != 0;
        }

        public static boolean putBoolean(android.content.ContentResolver r, String name, boolean value) {
            return LocalSettings.globalPutInt(r, name, value ? 1 : 0);
        }
    }

    public static class SettingsCloudData {
        public static boolean getCloudDataBoolean(android.content.ContentResolver r, String module, String key, boolean def) {
            return def;
        }

        public static long getCloudDataLong(android.content.ContentResolver r, String module, String key, long def) {
            return def;
        }

        public static String getCloudDataString(android.content.ContentResolver r, String module, String key, String def) {
            return def;
        }
    }

    public static class System {
        /** Read by reflection: the setting listing apps whose recents thumbnail is blurred. */
        public static final String MIUI_RECENTS_PRIVACY_THUMBNAIL_BLUR = "miui_recents_privacy_thumbnail_blur";

        public static boolean getBoolean(android.content.ContentResolver r, String name, boolean def) {
            return LocalSettings.systemGetInt(r, name, def ? 1 : 0) != 0;
        }

        public static boolean getBooleanForUser(android.content.ContentResolver r, String name, boolean def, int user) {
            return getBoolean(r, name, def);
        }

        /** HyperOS's order of the navigation keys (buttons mode); none here, the caller uses its default order. */
        public static java.util.ArrayList getScreenKeyOrder(android.content.Context context) {
            return null;
        }

        public static String getString(android.content.ContentResolver r, String name) {
            return LocalSettings.systemGetString(r, name);
        }

        public static String getStringForUser(android.content.ContentResolver r, String name, int user) {
            return getString(r, name);
        }

        public static boolean putBoolean(android.content.ContentResolver r, String name, boolean value) {
            return LocalSettings.systemPutInt(r, name, value ? 1 : 0);
        }

        public static boolean putBooleanForUser(android.content.ContentResolver r, String name, boolean value, int user) {
            return putBoolean(r, name, value);
        }

        public static boolean putString(android.content.ContentResolver r, String name, String value) {
            return LocalSettings.systemPutString(r, name, value);
        }
    }
}
