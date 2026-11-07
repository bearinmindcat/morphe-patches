package org.ungoogled.motolauncher;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

/**
 * Android calls a system launcher makes that an installed app may make too, but that fail for it: the patch sends them
 * here.
 */
public final class Calls {
    private static final String TAG = "UA";

    private Calls() {
    }

    /**
     * A content observer on a provider that does not exist (Motorola's settings and badge providers): Android 14+ throws a
     * SecurityException at an app that watches one; off Motorola there is nothing to watch, so it is skipped.
     */
    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no provider to observe: " + uri);
        }
    }

    /**
     * The overlays of the launcher's own package (a vendor's per-model theme overlays): listed by the system's
     * overlay service, which an installed app cannot reach (the manager has no service and throws). None here.
     */
    public static java.util.List<?> getOverlayInfosForTarget(android.content.om.OverlayManager manager, String target,
            android.os.UserHandle user) {
        try {
            Object list = manager.getClass().getMethod("getOverlayInfosForTarget", String.class, android.os.UserHandle.class)
                    .invoke(manager, target, user);
            if (list instanceof java.util.List) return (java.util.List<?>) list;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no overlays listed: " + e);
        }
        return new java.util.ArrayList<>();
    }

    /**
     * DeviceConfig (Launcher3's server-pushed device flags; a system API, so called by reflection): Android 13 lets only
     * apps with READ_DEVICE_CONFIG (a system permission) read it and throws a SecurityException at others (Android 15
     * lets any app); the flag then keeps its default.
     */
    public static boolean getBoolean(String namespace, String name, boolean fallback) {
        try {
            return (Boolean) Class.forName("android.provider.DeviceConfig")
                    .getMethod("getBoolean", String.class, String.class, boolean.class).invoke(null, namespace, name, fallback);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException && !(e.getCause() instanceof SecurityException)) {
                throw (RuntimeException) e.getCause();
            }
            return fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    /** ActivityManager.getCurrentUser() needs INTERACT_ACROSS_USERS: the app's own user is the one in front. */
    public static int getCurrentUser() {
        return Process.myUserHandle().hashCode();
    }
}
