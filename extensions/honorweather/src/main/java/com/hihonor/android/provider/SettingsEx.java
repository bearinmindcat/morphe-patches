package com.hihonor.android.provider;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off MagicOS (no Honor code). */
public class SettingsEx {
    public static class Secure {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return p3;
        }
    }
    public static class System {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return android.provider.Settings.System.getInt(p0, p1, p2);
        }
    }
}
