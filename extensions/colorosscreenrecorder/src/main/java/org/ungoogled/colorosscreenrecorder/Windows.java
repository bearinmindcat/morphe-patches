package org.ungoogled.colorosscreenrecorder;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * The recorder's floating windows (recording control, countdown, dialogs). On ColorOS it adds them as system windows
 * through ColorOS's AppPlatform (the bundled Tingle IPC hands it a "windowInner" window manager that adds windows in
 * AppPlatform's name, and the window's packageName is set to com.oplus.appplatform). Off ColorOS there is no
 * AppPlatform: Tingle answered null and every such window failed. Without AppPlatform they are added as the app's own
 * windows ("Display over other apps"), in its own name.
 */
public final class Windows {
    static final String APP_PLATFORM = "com.oplus.appplatform";
    private static Boolean appPlatform;

    private Windows() {
    }

    private static Application app() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether ColorOS's AppPlatform is installed (then everything goes its way). */
    static synchronized boolean hasAppPlatform(Context context) {
        if (appPlatform != null) return appPlatform;
        Context c = context != null ? context : app();
        if (c == null) return false;
        try {
            c.getPackageManager().getApplicationInfo(APP_PLATFORM, 0);
            appPlatform = true;
        } catch (PackageManager.NameNotFoundException e) {
            appPlatform = false;
        }
        return appPlatform;
    }

    /**
     * Tingle's getService(context, name): without AppPlatform, "windowInner" is the app's own window manager; null
     * leaves every other name (and everything on ColorOS) to Tingle.
     */
    public static Object tingleService(Context context, String name) {
        if (context == null || hasAppPlatform(context) || !"windowInner".equals(name)) return null;
        final WindowManager real = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (real == null) return null;
        return Proxy.newProxyInstance(WindowManager.class.getClassLoader(), new Class<?>[] {WindowManager.class},
                (proxy, method, args) -> {
                    if (args != null) {
                        for (Object arg : args) if (arg instanceof WindowManager.LayoutParams) ordinary((WindowManager.LayoutParams) arg);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // WindowManager.LayoutParams' hidden flags only system windows may carry (AppPlatform's windows are such).
    private static final int PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY = 0x00000008;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000;
    private static Field privateFlags;

    /**
     * An AppPlatform window as one the app may add itself: the app's own name, an overlay ("Display over other apps")
     * where it asked for a system window type, without the hidden flags only system windows may carry.
     */
    static void ordinary(WindowManager.LayoutParams params) {
        if (APP_PLATFORM.equals(params.packageName)) params.packageName = null;
        int type = params.type;
        boolean app = (type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW && type <= WindowManager.LayoutParams.LAST_SUB_WINDOW)
                || type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (!app && type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        try {
            if (privateFlags == null) privateFlags = WindowManager.LayoutParams.class.getField("privateFlags");
            int flags = privateFlags.getInt(params);
            privateFlags.setInt(params, flags & ~(PRIVATE_FLAG_TRUSTED_OVERLAY | PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Hidden field unavailable: Android refuses the window as before.
        }
    }

    /** Tingle's release(context, name): nothing to undo without AppPlatform. */
    public static boolean skipTingleRelease(Context context, String name) {
        return context != null && !hasAppPlatform(context) && "windowInner".equals(name);
    }

    /** Every write of a window's packageName: AppPlatform's name only where AppPlatform exists, else the app's own. */
    public static void setPackageName(WindowManager.LayoutParams params, String packageName) {
        if (params == null) return;
        if (APP_PLATFORM.equals(packageName) && !hasAppPlatform(null)) packageName = null; // Android fills in ours
        params.packageName = packageName;
    }

    /**
     * q4.d.isAppEnabled for AppPlatform: the recorder refuses to record (and nags in its settings) while AppPlatform is
     * disabled; without AppPlatform installed it records without it (see Projection and above), so the check passes.
     * -1 = ask Android (AppPlatform installed, or another package).
     */
    public static int appEnabled(Context context, String packageName) {
        if (!APP_PLATFORM.equals(packageName) || hasAppPlatform(context)) return -1;
        return 1;
    }
}
