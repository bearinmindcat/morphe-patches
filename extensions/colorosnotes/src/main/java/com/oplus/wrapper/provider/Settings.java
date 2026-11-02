package com.oplus.wrapper.provider;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Notes uses, answering "absent" off ColorOS (no OPPO code). */
public class Settings {
    public static class Secure {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2) {
            return p2;
        }
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            try { return android.provider.Settings.Secure.getInt(p0, p1, p2); } catch (RuntimeException e) { return p2; }
        }
        public static java.lang.String getStringForUser(android.content.ContentResolver p0, java.lang.String p1, int p2) {
            try { return android.provider.Settings.Secure.getString(p0, p1); } catch (RuntimeException e) { return null; }
        }
    }
    public static class System {
        public static int getIntForUser(android.content.ContentResolver p0, java.lang.String p1, int p2, int p3) {
            try { return android.provider.Settings.System.getInt(p0, p1, p2); } catch (RuntimeException e) { return p2; }
        }
    }
}
