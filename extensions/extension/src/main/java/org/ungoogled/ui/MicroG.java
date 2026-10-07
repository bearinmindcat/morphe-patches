package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ContentProviderClient;
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

    private static boolean answers(PackageManager pm, String action) {
        ResolveInfo r = pm.resolveService(new Intent(action).setPackage(PACKAGE), 0);
        return r != null && r.serviceInfo != null && r.serviceInfo.exported && r.serviceInfo.enabled
                && PACKAGE.equals(r.serviceInfo.packageName);
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

    /**
     * microG hands this app the user's Google accounts. MicroG-RE does, from 7.2.1, by
     * honouring the stock package and certificate the manifest names; ReVanced GmsCore
     * 0.3 does not and refuses the account provider ("missing google package permission
     * or GET_ACCOUNTS"), so a sign-in there never reaches Maps. Asks the provider the
     * same question Maps asks. Off the main thread: it may have to start microG. And
     * through an unstable reference -- with a stable one, Android kills Maps along with
     * microG if microG's process dies mid-call, which a slow start can make it do.
     */
    static boolean sharesAccounts(Context c) {
        ContentProviderClient client = null;
        try {
            client = c.getContentResolver().acquireUnstableContentProviderClient(
                    Uri.parse("content://" + PACKAGE + ".auth.accounts"));
            if (client == null) return true;   // no provider to ask: do not nag
            client.call("get_accounts", "app.revanced", null);
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (Throwable t) {
            return true;   // could not tell: do not nag
        } finally {
            if (client != null) client.close();
        }
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
    private static final String KEY_QUIET_ACCOUNTS = "microg_quiet_accounts";

    /**
     * When the main screen first shows, once per launch: say so if microG is missing,
     * or is one that will not let Maps see the account. Maps works either way, signed
     * out -- the map, search and navigation need no account. Each can be silenced.
     */
    static final class MissingNotice implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            if (asked || !"com.google.android.maps.MapsActivity".equals(a.getClass().getName())) return;
            asked = true;
            SharedPreferences prefs = a.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
            new Thread(() -> {
                // Not in the middle of Maps' own start, which binds microG a dozen times.
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                String key, title, message;
                if (!installed(a)) {
                    key = KEY_QUIET_MISSING;
                    title = "microG is not installed";
                    message = "Signing in, saved places, Timeline and location sharing need MicroG-RE "
                            + "7.2.1 or newer. Maps works without it, signed out.";
                } else if (!sharesAccounts(a)) {
                    key = KEY_QUIET_ACCOUNTS;
                    title = "This microG can't sign Maps in";
                    message = "The installed microG (ReVanced GmsCore, or an older MicroG-RE) does not give "
                            + "Maps your Google account, so signing in won't take. MicroG-RE 7.2.1 or newer does. "
                            + "Maps works signed out either way.";
                } else {
                    return;
                }
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
