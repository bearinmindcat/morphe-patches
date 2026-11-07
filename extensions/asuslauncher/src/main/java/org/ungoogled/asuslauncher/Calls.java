package org.ungoogled.asuslauncher;

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
     * A content observer on a provider that does not exist (ASUS's settings and badge providers): Android 14+ throws a
     * SecurityException at an app that watches one; off ZenUI there is nothing to watch, so it is skipped.
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

    /*
     * DeviceConfig (the launcher's server-pushed feature flags; a system API, so called by reflection): Android 13 lets
     * only apps with READ_DEVICE_CONFIG (a system permission) read it and throws a SecurityException at others (Android
     * 15 lets any app); the flag then keeps its default.
     */

    private static Object deviceConfig(Object fallback, String name, Class<?>[] types, Object... args) {
        try {
            return Class.forName("android.provider.DeviceConfig").getMethod(name, types).invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) return fallback;
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            return fallback;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    public static boolean getBoolean(String namespace, String name, boolean fallback) {
        return (Boolean) deviceConfig(fallback, "getBoolean", new Class<?>[] { String.class, String.class, boolean.class },
                namespace, name, fallback);
    }

    public static float getFloat(String namespace, String name, float fallback) {
        return (Float) deviceConfig(fallback, "getFloat", new Class<?>[] { String.class, String.class, float.class },
                namespace, name, fallback);
    }

    public static String getString(String namespace, String name, String fallback) {
        return (String) deviceConfig(fallback, "getString", new Class<?>[] { String.class, String.class, String.class },
                namespace, name, fallback);
    }

    /** The listener is a DeviceConfig.OnPropertiesChangedListener. */
    public static void addOnPropertiesChangedListener(String namespace, java.util.concurrent.Executor executor, Object listener) {
        try {
            Class<?> type = Class.forName("android.provider.DeviceConfig$OnPropertiesChangedListener");
            deviceConfig(null, "addOnPropertiesChangedListener",
                    new Class<?>[] { String.class, java.util.concurrent.Executor.class, type }, namespace, executor, listener);
        } catch (ClassNotFoundException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no DeviceConfig updates: " + namespace);
        }
    }

    /** ActivityManager.getCurrentUser() needs INTERACT_ACROSS_USERS: the app's own user is the one in front. */
    public static int getCurrentUser() {
        return Process.myUserHandle().hashCode();
    }
}
