package org.ungoogled.samsungclone;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Names of a sibling app (Samsung Phone and Samsung Contacts open each other's screens and providers) in a renamed
 * copy: the renamed sibling's where it is installed and signed like this app (a clone pair made with the same key),
 * Samsung's own otherwise. The patch replaces each such name in the app with a generated method of this class that
 * calls pick.
 */
public final class Siblings {
    private static final String TAG = "UA";
    /** Clone packages found installed (true) or not (false), with when that was last asked. */
    private static final ConcurrentHashMap<String, Boolean> FOUND = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ASKED = new ConcurrentHashMap<>();
    /** How long "not installed" holds before the phone is asked again (the sibling may be installed meanwhile). */
    private static final long RECHECK_MS = 15_000;

    private Siblings() {
    }

    /** [clone] where [clonePackage] is installed and signed like this app, else [stock]. Never throws. */
    public static String pick(String stock, String clone, String clonePackage) {
        try {
            return installed(clonePackage) ? clone : stock;
        } catch (Throwable t) {
            return stock;
        }
    }

    private static boolean installed(String pkg) {
        Boolean found = FOUND.get(pkg);
        if (Boolean.TRUE.equals(found)) return true;
        Long asked = ASKED.get(pkg);
        long now = SystemClock.elapsedRealtime();
        if (found != null && asked != null && now - asked < RECHECK_MS) return false;
        Context context = context();
        // Too early in start-up to ask: Samsung's own, without remembering it.
        if (context == null) return false;
        boolean yes;
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(pkg, 0);
            yes = pm.checkSignatures(context.getPackageName(), pkg) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException e) {
            yes = false;
        } catch (RuntimeException e) {
            yes = false;
        }
        if (found == null || found != yes) Log.i(TAG, "sibling " + pkg + (yes ? " installed" : " not installed"));
        FOUND.put(pkg, yes);
        ASKED.put(pkg, now);
        return yes;
    }

    private static volatile Context app;

    private static Context context() {
        Context c = app;
        if (c != null) return c;
        try {
            Object application = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                c = (Context) application;
                app = c;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Not yet: Samsung's own names for now.
        }
        return c;
    }
}
