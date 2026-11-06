package com.motorola.android.provider;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;

import org.ungoogled.motolauncher.LocalSettings;

/**
 * Stand-in for Motorola's settings tables (a Moto system provider). Off Motorola there is no such provider: the
 * launcher's reads and writes go to its local settings (see LocalSettings: Android's table where it has the key, else
 * the launcher's own copy, announced on Android's settings URI), so options it writes are read back and other keys
 * answer the caller's default. Carries Motorola's names and signatures only.
 */
public final class MotorolaSettings {
    private MotorolaSettings() {
    }

    /** Thrown where a key has no value, as Android's Settings.SettingNotFoundException. */
    public static class SettingNotFoundException extends Exception {
        public SettingNotFoundException(String message) {
            super(message);
        }
    }

    public static final class Global {
        private Global() {
        }

        public static String getString(ContentResolver r, String name) {
            return LocalSettings.globalGetString(r, name);
        }

        public static int getInt(ContentResolver r, String name, int def) {
            return LocalSettings.globalGetInt(r, name, def);
        }

        public static int getInt(ContentResolver r, String name) throws SettingNotFoundException {
            try {
                return LocalSettings.globalGetInt(r, name);
            } catch (Settings.SettingNotFoundException e) {
                throw new SettingNotFoundException(name);
            }
        }

        public static boolean putString(ContentResolver r, String name, String value) {
            return LocalSettings.globalPutString(r, name, value);
        }

        public static Uri getUriFor(String name) {
            return Settings.Global.getUriFor(name);
        }
    }

    public static final class Secure {
        private Secure() {
        }

        public static int getInt(ContentResolver r, String name, int def) {
            return LocalSettings.secureGetInt(r, name, def);
        }

        public static int getInt(ContentResolver r, String name) throws SettingNotFoundException {
            try {
                return LocalSettings.secureGetInt(r, name);
            } catch (Settings.SettingNotFoundException e) {
                throw new SettingNotFoundException(name);
            }
        }

        public static Uri getUriFor(String name) {
            return Settings.Secure.getUriFor(name);
        }
    }

    public static final class System {
        private System() {
        }

        public static int getInt(ContentResolver r, String name, int def) {
            return LocalSettings.systemGetInt(r, name, def);
        }

        public static int getIntForUser(ContentResolver r, String name, int def, int user) {
            return LocalSettings.systemGetInt(r, name, def);
        }

        public static Uri getUriFor(String name) {
            return Settings.System.getUriFor(name);
        }
    }
}
