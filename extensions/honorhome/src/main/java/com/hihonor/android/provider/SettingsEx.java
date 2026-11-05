package com.hihonor.android.provider;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Home uses, answering "absent" off MagicOS (no Honor code). */
public class SettingsEx {
    public static class Secure {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return p3;
        }
        public static java.lang.String getStringForUser(android.content.ContentResolver p0, java.lang.String p1, int p2) {
            return "";
        }
        public static boolean putStringForUser(android.content.ContentResolver p0, java.lang.String p1, java.lang.String p2, int p3) {
            return false;
        }
    }
    public static class System {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return android.provider.Settings.System.getInt(p0, p1, p2);
        }
        public static boolean putIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return false;
        }
    }
    public static class Systemex {
        public static java.lang.String getString(android.content.ContentResolver p0, java.lang.String p1) {
            return null;
        }
    }
}
