package org.ungoogled.xiaomilauncher;

/** What a system home screen may do and this copy of the HyperOS launcher may not. */
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

    /**
     * Android's own launch options where the launcher may not animate launches remotely (see remoteAnimations), else
     * null: HyperOS's ActivityOptionsCompat.makeRemoteAnimation builds its options by reflection on a call only a
     * privileged home app may make, which here gave null and stopped every app launch.
     */
    public static android.app.ActivityOptions basicOptions() {
        return remoteAnimations(App.context()) ? null : android.app.ActivityOptions.makeBasic();
    }

    /**
     * Window.addFlags: HyperOS draws a window's translucent status/navigation bar fully transparent, Android draws a
     * grey scrim over it (a grey band over the launcher's wallpaper). Those two flags become what HyperOS shows: the
     * window draws the bars' backgrounds itself, transparent, with no contrast scrim. Other flags are added as asked.
     */
    @SuppressWarnings("deprecation")
    public static void addFlags(android.view.Window window, int flags) {
        int status = android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        int navigation = android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        int translucent = flags & (status | navigation);
        if (translucent == 0) {
            window.addFlags(flags);
            return;
        }
        window.addFlags((flags & ~translucent) | android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if ((translucent & status) != 0) {
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setStatusBarContrastEnforced(false);
        }
        if ((translucent & navigation) != 0) {
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /**
     * PackageManager.deletePackage / deletePackageAsUser: deleting needs DELETE_PACKAGES (system only). Android's own
     * uninstall dialog is opened instead, and the launcher's observer hears "not deleted" at once, so the icon it took
     * away comes back; when the user confirms in the dialog, Android's package-removed broadcast removes the app from
     * the home screen as for any uninstall.
     */
    public static void deletePackage(android.content.pm.PackageManager pm, String packageName, Object observer, int flags) {
        uninstall(packageName, observer);
    }

    public static void deletePackageAsUser(android.content.pm.PackageManager pm, String packageName, Object observer,
            int flags, int user) {
        uninstall(packageName, observer);
    }

    private static void uninstall(String packageName, Object observer) {
        android.content.Context c = App.context();
        // The launcher's Application sends its own starts through its launch animations: Android's context starts it.
        if (c instanceof android.content.ContextWrapper) c = ((android.content.ContextWrapper) c).getBaseContext();
        if (c != null && packageName != null) {
            try {
                c.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                        android.net.Uri.fromParts("package", packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                android.util.Log.w("UA", "no uninstall dialog: " + e);
            }
        }
        if (observer == null) return;
        try {
            observer.getClass().getMethod("packageDeleted", String.class, int.class).invoke(observer, packageName, -1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            android.util.Log.w("UA", "delete observer not told: " + e);
        }
    }
}
