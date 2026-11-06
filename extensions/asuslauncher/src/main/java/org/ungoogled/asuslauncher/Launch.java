package org.ungoogled.asuslauncher;

/** What a system home screen may do and this copy of ASUS Launcher may not. */
public final class Launch {
    private static Boolean remoteAnimations;

    private Launch() {
    }

    /**
     * Whether launches may use remote animations and the launcher may register its home transitions: Android lets only
     * a home app holding CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (privileged) do it; anything else gets a
     * SecurityException and the app does not open. The patch uses Android's own animations otherwise.
     */
    public static boolean remoteAnimations(android.content.Context c) {
        if (remoteAnimations == null) {
            if (c == null) return false;
            remoteAnimations = c.checkSelfPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return remoteAnimations;
    }

    /**
     * Whether the system launcher's widget holder can work here: it registers a per-widget update listener
     * (AppWidgetHost.setListener), which Android only lets the system use. The patch uses Launcher3's plain holder
     * otherwise (same widget host id), which gets its updates the way any home app does.
     */
    public static boolean widgetListener() {
        try {
            Class<?> listener = Class.forName("android.appwidget.AppWidgetHost$AppWidgetHostListener");
            android.appwidget.AppWidgetHost.class.getMethod("setListener", int.class, listener);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * The app prediction service (an AppPredictionManager, a system API) where the launcher may ask it, else none: it
     * only answers a caller with usage access (PACKAGE_USAGE_STATS), and the launcher then shows no suggested apps.
     */
    public static Object predictions(Object manager) {
        android.content.Context c = App.context();
        if (c == null || c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return manager;
    }
}
