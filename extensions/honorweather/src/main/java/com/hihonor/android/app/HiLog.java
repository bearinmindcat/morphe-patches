package com.hihonor.android.app;

/**
 * Generated stand-in (.github/scripts/honor-standins), answered by hand: MagicOS's HiLog writes to the phone's log, so
 * errors and warnings (and the info/debug lines the app itself enables) go to Android's log, as on MagicOS.
 */
public class HiLog {
    private static int log(int priority, String tag, String format, Object[] args) {
        String message = format;
        if (format != null && args != null && args.length > 0) {
            // HiLog's "%{public}s" / "%{private}d" are printf conversions with a privacy flag.
            try {
                message = String.format(format.replaceAll("%\\{(public|private)\\}", "%"), args);
            } catch (RuntimeException e) {
                message = format;
            }
        }
        return android.util.Log.println(priority, tag == null ? "HiLog" : tag, message == null ? "null" : message);
    }
    public static int authPrintf(com.hihonor.android.app.HiLogLabel p0, java.lang.String p1, java.lang.String p2, java.lang.String p3, java.lang.String p4, java.lang.String p5, java.lang.Object[] p6) {
        return 0;
    }
    public static int d(int p0, java.lang.String p1, boolean p2, java.lang.String p3, java.lang.Object[] p4) {
        return log(android.util.Log.DEBUG, p1, p3, p4);
    }
    public static int e(int p0, java.lang.String p1, boolean p2, java.lang.String p3, java.lang.Object[] p4) {
        return log(android.util.Log.ERROR, p1, p3, p4);
    }
    public static int i(int p0, java.lang.String p1, boolean p2, java.lang.String p3, java.lang.Object[] p4) {
        return log(android.util.Log.INFO, p1, p3, p4);
    }
    public static int w(int p0, java.lang.String p1, boolean p2, java.lang.String p3, java.lang.Object[] p4) {
        return log(android.util.Log.WARN, p1, p3, p4);
    }
}
