package org.ungoogled.nothinglauncher;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * What a system home screen may do and this copy of Nothing Launcher may not. It animates every launch with a remote
 * animation, which Android only lets a privileged home app start: anything else gets a SecurityException and the app
 * does not open, so launches use Android's own animation. Its suggested apps come from Android's app prediction
 * service, which only answers a caller with usage access (PACKAGE_USAGE_STATS): without it there are no suggestions.
 */
public final class Launch {
    private static Context context;
    private static Boolean remoteAnimations;

    private Launch() {
    }

    /** The application context (ActivityThread's, hidden but open to apps). */
    private static Context context() {
        if (context == null) {
            try {
                context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return context;
    }

    /** Whether launches may use remote animations (the patch skips the launcher's own launch animation otherwise). */
    public static boolean remoteAnimations(Context c) {
        if (remoteAnimations == null) {
            if (c == null) c = context();
            if (c == null) return false;
            remoteAnimations = c.checkSelfPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return remoteAnimations;
    }

    /**
     * The home screen menu's "Customization": Nothing's wallpaper app where the phone has it (it links to the launcher's
     * Customization page), else that page directly. Returns whether it was opened here.
     */
    public static boolean customization(android.view.View view) {
        Context c = view.getContext();
        try {
            c.getPackageManager().getPackageInfo("com.nothing.wallpaper", 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            // Not a Nothing phone: the launcher's own page.
        }
        try {
            c.startActivity(new android.content.Intent("com.nothing.launcher.customisation_settings")
                    .setPackage(c.getPackageName())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the system launcher's widget holder can work here: it registers a per-widget update listener
     * (AppWidgetHost.setListener), which Android only lets the system use. The patch uses Launcher3's own holder
     * otherwise.
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
     * 3.3.14 (Android 15) chooses the system holder by a feature flag ("widget host in background"): it is used only
     * where its update listener works, else Launcher3's own holder.
     */
    public static boolean widgetHostInBackground(boolean flag) {
        return flag && widgetListener();
    }

    /**
     * Whether widget colors can be derived from the wallpaper: the launcher uses Android's hidden color-science class
     * (Cam) and the wallpaper's full color histogram (WallpaperColors.getAllColors), which only the system may use.
     */
    public static boolean wallpaperColors() {
        try {
            Class.forName("com.android.internal.graphics.cam.Cam").getMethod("fromInt", int.class);
            android.app.WallpaperColors.class.getMethod("getAllColors");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether a view the launcher inflates is a clock that the system launcher would hand its shared time-tick listener
     * (an object only the system may create): such clocks are inflated as in any other app.
     */
    public static boolean ownClock(String name) {
        return "TextClock".equals(name) || "android.widget.TextClock".equals(name)
                || "AnalogClock".equals(name) || "android.widget.AnalogClock".equals(name);
    }

    /**
     * The app prediction service (an AppPredictionManager, a system API) where the launcher may ask it, else none: the
     * launcher then shows no suggestions.
     */
    public static Object predictions(Object manager) {
        Context c = context();
        if (c == null || c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return manager;
    }
}
