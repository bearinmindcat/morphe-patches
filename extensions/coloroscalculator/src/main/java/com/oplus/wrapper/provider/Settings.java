package com.oplus.wrapper.provider;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Calculator uses, answering "absent" off ColorOS (no OPPO code). */
public class Settings {
    public static class System {
        public static boolean putIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            try { return android.provider.Settings.System.putInt(p0, p1, p2); } catch (RuntimeException e) { return false; }
        }
    }
}
