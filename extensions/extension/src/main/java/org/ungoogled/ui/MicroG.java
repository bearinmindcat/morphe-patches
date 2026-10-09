package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

/**
 * microG Maps' runtime half (Add microG support). Maps signs in, syncs and gets
 * its push messages through microG instead of Google Play services: the patch points
 * every Play services name in Maps' code and manifest at microG's package, and the
 * manifest tells microG which app to vouch for (the stock package and certificate).
 *
 * Two microG apps are in use and both install as app.revanced.android.gms:
 * ReVanced GmsCore answers Google's own service actions, MicroG-RE renamed some of
 * them under app.revanced -- the location service among them -- so that one is
 * picked at runtime.
 */
public final class MicroG {
    static final String PACKAGE = "app.revanced.android.gms";
    static final String GOOGLE_LOCATION_ACTION = "com.google.android.location.internal.GoogleLocationManagerService.START";
    static final String RENAMED_LOCATION_ACTION = "app.revanced.android.location.internal.GoogleLocationManagerService.START";
    private static final String DOWNLOAD = "https://github.com/MorpheApp/MicroG-RE/releases";

    private static boolean tracked, asked;

    private MicroG() {}

    /** microG is installed and enabled. */
    static boolean installed(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(PACKAGE, 0).enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * In place of Maps' two location-service action getters: the action the installed
     * microG answers. Not cached -- microG can be swapped or updated while Maps runs.
     */
    public static String locationAction() {
        Context c = Shapes.appContext();
        if (c == null) return GOOGLE_LOCATION_ACTION;
        try {
            PackageManager pm = c.getPackageManager();
            if (answers(pm, GOOGLE_LOCATION_ACTION)) return GOOGLE_LOCATION_ACTION;
            if (answers(pm, RENAMED_LOCATION_ACTION)) return RENAMED_LOCATION_ACTION;
        } catch (Throwable ignored) {}
        return GOOGLE_LOCATION_ACTION;
    }

    /**
     * In place of a Play services client's service action, for the services MicroG-RE answers
     * only under app.revanced names -- Location sharing's reporter and location reporting
     * (issue #30): the name the installed microG answers, Google's own first (ReVanced GmsCore).
     */
    public static String serviceAction(String google) {
        Context c = Shapes.appContext();
        if (c == null || !google.startsWith(PLAY_SERVICES + ".")) return google;
        try {
            PackageManager pm = c.getPackageManager();
            if (answers(pm, google)) return google;
            String renamed = PACKAGE + google.substring(PLAY_SERVICES.length());
            if (answers(pm, renamed)) return renamed;
        } catch (Throwable ignored) {}
        return google;
    }

    private static boolean answers(PackageManager pm, String action) {
        ResolveInfo r = pm.resolveService(new Intent(action).setPackage(PACKAGE), 0);
        return r != null && r.serviceInfo != null && r.serviceInfo.exported && r.serviceInfo.enabled
                && PACKAGE.equals(r.serviceInfo.packageName);
    }

    /** Google's own Play services. */
    private static final String PLAY_SERVICES = "com.google.android.gms";
    private static volatile String modules;   // decided at process start

    /** From Shapes.processStart, before anything in Maps runs: where Play services modules come from. */
    static void processStart(Context c) {
        modules = playServicesPresent(c) ? PLAY_SERVICES : PACKAGE;
    }

    /** Google's Play services is installed, enabled or not. */
    private static boolean playServicesPresent(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(PLAY_SERVICES, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * In place of the Play services package in Maps' module loader (DynamiteModule): Google's
     * own Play services whenever it is installed, and microG only on phones without it. Its
     * modules are code Maps runs in its own process -- Cronet, certificate checks, the security
     * provider -- not account services, which stay with microG. ReVanced GmsCore builds
     * modules from Google's Play services APK whenever that is installed, without the context
     * Google's own loader sets up: on Play services 26.36 its Cronet started but no request
     * ever finished, and Maps sat on its splash screen. A disabled Play services offers no
     * modules, which Maps handles as on a phone without any.
     */
    public static String modulesPackage() {
        String m = modules;
        if (m == null) {
            Context c = Shapes.appContext();
            m = c != null && playServicesPresent(c) ? PLAY_SERVICES : PACKAGE;
            if (c != null) modules = m;
        }
        return m;
    }

    /** The module provider's authority in [modulesPackage]. */
    public static String modulesAuthority() {
        return modulesPackage() + ".chimera";
    }

    /**
     * In place of Maps' instantiation of a Play services module's class (DynamiteModule's
     * instantiate): the same, except that a class which throws while it is constructed counts
     * as a missing module -- Maps' own module exception, [failure], which every caller handles
     * -- instead of crashing Maps. ReVanced GmsCore builds modules from Google's Play services
     * APK whenever Google's is installed too, and outside Google's own module loader Play
     * services 26.36's GoogleCertificatesImpl throws "Missing DynamiteApplicationContext".
     */
    public static android.os.IBinder moduleObject(ClassLoader loader, String name, Class<?> failure) {
        try {
            return (android.os.IBinder) loader.loadClass(name).newInstance();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            android.util.Log.w("UA", "Play services module class " + name + " did not load", t);
            Throwable missing;
            try {
                missing = (Throwable) failure.getConstructor(String.class, Throwable.class)
                        .newInstance("Failed to instantiate module class: " + name, t);
            } catch (Throwable reflection) {
                missing = new IllegalStateException(t);
            }
            throw MicroG.<RuntimeException>sneaky(missing);
        }
    }

    private static volatile java.util.concurrent.Executor binds;

    /**
     * The executor Maps' Google-auth helper connects to the token service on, in place of none,
     * which means the main thread. On its first start Maps waits on its main thread for each
     * Google account's id (the "Make it your map" page), and that id comes over a connection
     * delivered on the main thread: with an account in microG, Maps froze on its splash screen.
     */
    public static java.util.concurrent.Executor bindExecutor() {
        java.util.concurrent.Executor e = binds;
        if (e == null) {
            synchronized (MicroG.class) {
                if (binds == null) {
                    binds = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "UA-gms-bind");
                        t.setDaemon(true);
                        return t;
                    });
                }
                e = binds;
            }
        }
        return e;
    }

    /**
     * In place of the auth helper's wait for the token service, which had no time limit: a
     * service that never connects (microG turning Maps away, say) now fails after 15 s as an
     * unreachable service, which Maps handles, instead of holding Maps forever.
     */
    public static Object takeService(java.util.concurrent.BlockingQueue<?> queue) {
        try {
            Object service = queue.poll(15, java.util.concurrent.TimeUnit.SECONDS);
            if (service == null) {
                throw MicroG.<RuntimeException>sneaky(new java.util.concurrent.TimeoutException("the token service did not connect"));
            }
            return service;
        } catch (InterruptedException e) {
            throw MicroG.<RuntimeException>sneaky(e);
        }
    }

    /** Rewritten by the patch: the name of Play services' Status.startResolutionForResult(Activity, int). */
    static String resolveMethod() { return ""; }

    /**
     * In place of Maps' Status.startResolutionForResult -- the "turn on location" request, and Play
     * services' resolvable errors: MicroG-RE answers the location settings check with a dialog under
     * Google's action in its own package, which it does not answer, and the ActivityNotFoundException
     * crashed Maps when my location was tapped with Android's location off (issue #27). Android's own
     * location settings open instead while location is off; otherwise nothing happens. Every other
     * failure goes to Maps as before.
     */
    public static void resolve(Object status, Activity activity, int requestCode) {
        try {
            status.getClass().getMethod(resolveMethod(), Activity.class, int.class).invoke(status, activity, requestCode);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (!(cause instanceof android.content.ActivityNotFoundException)) throw MicroG.<RuntimeException>sneaky(cause);
            android.util.Log.w("UA", "Play services resolution has no screen", cause);
            if (!locationOn(activity)) {
                try {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Throwable ignored) {}
            }
        } catch (ReflectiveOperationException e) {
            throw MicroG.<RuntimeException>sneaky(e);
        }
    }

    private static boolean locationOn(Context c) {
        try {
            android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
            return lm != null && lm.isLocationEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Throws a checked exception (Maps' module exception) from code that does not declare it. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private static volatile Boolean cronet;   // null = not tried yet

    /**
     * In place of Play services' Cronet provider's answer to isEnabled(), which is
     * {@code installed}: whether it can really build an engine. microG hands Maps its
     * Cronet as a module, and ReVanced GmsCore builds that module from Google's Play
     * services APK whenever Google's is installed as well -- an APK without the
     * engine, so createBuilder() threw and Maps died at start. A provider that cannot
     * build one says it is not there, and Maps takes its next: the Java one it bundles.
     * Tried once per process; the module does not change while Maps runs.
     */
    public static boolean cronetUsable(Object provider, boolean installed) {
        if (!installed) return false;
        Boolean v = cronet;
        if (v == null) {
            try {
                provider.getClass().getMethod("createBuilder").invoke(provider);
                v = Boolean.TRUE;
            } catch (Throwable t) {
                v = Boolean.FALSE;
            }
            cronet = v;
        }
        return v;
    }

    /** From Shapes.wrap, at every Activity attach: once per process, watch for the main screen. */
    static void track(Context c) {
        if (tracked) return;
        Context app = c.getApplicationContext();
        if (!(app instanceof Application)) return;
        tracked = true;
        ((Application) app).registerActivityLifecycleCallbacks(new MissingNotice());
    }

    private static final String KEY_QUIET_MISSING = "microg_quiet_missing";

    /**
     * When the main screen first shows, once per launch: say so if microG is missing. Maps
     * works without it, signed out -- the map, search and navigation need no account. It
     * can be silenced.
     */
    static final class MissingNotice implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            if (asked || !"com.google.android.maps.MapsActivity".equals(a.getClass().getName())) return;
            asked = true;
            SharedPreferences prefs = a.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
            new Thread(() -> {
                // Not in the middle of Maps' own start, which binds microG a dozen times.
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                if (installed(a)) return;
                String key = KEY_QUIET_MISSING;
                String title = "microG is not installed";
                String message = "Signing in, saved places, Timeline and location sharing need microG "
                        + "(MicroG-RE or ReVanced GmsCore). Maps works without it, signed out.";
                if (prefs.getBoolean(key, false)) return;
                a.runOnUiThread(() -> show(a, prefs, key, title, message));
            }, "UA-microg-check").start();
        }

        private static void show(Activity a, SharedPreferences prefs, String key, String title, String message) {
            if (a.isFinishing() || a.isDestroyed()) return;
            try {
                new AlertDialog.Builder(a)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("Get MicroG-RE", (d, w) -> {
                            try {
                                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Throwable ignored) {}
                        })
                        .setNegativeButton("Not now", null)
                        .setNeutralButton("Don't show again", (d, w) -> prefs.edit().putBoolean(key, true).apply())
                        .show();
            } catch (Throwable ignored) {}
        }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}
