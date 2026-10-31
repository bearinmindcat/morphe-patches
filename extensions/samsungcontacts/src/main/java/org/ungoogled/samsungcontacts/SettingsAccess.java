package org.ungoogled.samsungcontacts;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

/**
 * Every Settings.Secure / System / Global read and write in Contacts comes here. On One UI it is a preloaded app that
 * may write settings; elsewhere the writes need permissions it does not hold, and a refused call threw
 * SecurityException, which crashed the app (the Contacts tab first). A refused write now answers false and a refused
 * read (Android's hidden settings) answers "not set".
 */
public final class SettingsAccess {
    private static final String TAG = "UA";

    private SettingsAccess() {
    }

    private static boolean refused(String table, String name, SecurityException e) {
        Log.w(TAG, "Settings." + table + " " + name + " refused: " + e.getMessage());
        return false;
    }

    // Settings.Secure

    public static int secureGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.Secure.getInt(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static int secureGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Secure.getInt(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static float secureGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.Secure.getFloat(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static float secureGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Secure.getFloat(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static String secureGetString(ContentResolver r, String name) {
        try {
            return Settings.Secure.getString(r, name);
        } catch (SecurityException e) {
            return null;
        }
    }

    public static boolean securePutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.Secure.putInt(r, name, value);
        } catch (SecurityException e) {
            return refused("Secure", name, e);
        }
    }

    public static boolean securePutString(ContentResolver r, String name, String value) {
        try {
            return Settings.Secure.putString(r, name, value);
        } catch (SecurityException e) {
            return refused("Secure", name, e);
        }
    }

    // Settings.System

    public static int systemGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.System.getInt(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static int systemGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.System.getInt(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static float systemGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.System.getFloat(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static float systemGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.System.getFloat(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static String systemGetString(ContentResolver r, String name) {
        try {
            return Settings.System.getString(r, name);
        } catch (SecurityException e) {
            return null;
        }
    }

    public static boolean systemPutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.System.putInt(r, name, value);
        } catch (SecurityException e) {
            return refused("System", name, e);
        }
    }

    public static boolean systemPutString(ContentResolver r, String name, String value) {
        try {
            return Settings.System.putString(r, name, value);
        } catch (SecurityException e) {
            return refused("System", name, e);
        }
    }

    // Settings.Global

    public static int globalGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.Global.getInt(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static int globalGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Global.getInt(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static float globalGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.Global.getFloat(r, name, def);
        } catch (SecurityException e) {
            return def;
        }
    }

    public static float globalGetFloat(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Global.getFloat(r, name);
        } catch (SecurityException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static String globalGetString(ContentResolver r, String name) {
        try {
            return Settings.Global.getString(r, name);
        } catch (SecurityException e) {
            return null;
        }
    }

    public static boolean globalPutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.Global.putInt(r, name, value);
        } catch (SecurityException e) {
            return refused("Global", name, e);
        }
    }

    public static boolean globalPutString(ContentResolver r, String name, String value) {
        try {
            return Settings.Global.putString(r, name, value);
        } catch (SecurityException e) {
            return refused("Global", name, e);
        }
    }
}
