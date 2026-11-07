package org.ungoogled.coloroslauncher;

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
     * A content observer on a provider that does not exist (ColorOS's feature and settings providers): Android 14+
     * throws a SecurityException at an app that watches one; off ColorOS there is nothing to watch, so it is skipped.
     */
    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no provider to observe: " + uri);
        }
    }

    /*
     * DeviceConfig (the launcher's server-pushed feature flags; a system API, so called by reflection): Android 11 lets
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
