package com.hihonor.android.provider;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Clock uses, answering "absent" off MagicOS (no Honor code). */
public class SettingsEx {
    public static class Global {
        public static int getZenModeOff() {
            return 0;
        }
        public static java.lang.String getZen_Mode() {
            return "zen_mode";
        }
    }
    public static class System {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            return android.provider.Settings.System.getInt(p0, p1, p2);
        }
    }
}
