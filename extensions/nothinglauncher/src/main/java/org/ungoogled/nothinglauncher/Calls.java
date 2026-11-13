package org.ungoogled.nothinglauncher;

/** Android calls a system launcher makes that an installed app may make too, but that fail for it: the patch sends them here. */
public final class Calls {
    private Calls() {
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
}
