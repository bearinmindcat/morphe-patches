package org.ungoogled.waze;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.widget.Toast;

/**
 * Notification access for Any media app. Some music apps -- Metrolist among them -- let only trusted controllers
 * browse their media (Android Auto, the system), and Android trusts an app whose notification listener the user
 * allowed. Waze reads nothing here: being allowed is all that counts, so the listener lets the system unbind it
 * right away (the access stays). A root mount install keeps the stock manifest, so it has no listener to allow.
 */
public final class MediaAccess extends NotificationListenerService {
    private static final String TAG = "UA";
    private static final String KEY_ASKED = "media_access_asked";
    private static final String GREYED = "If it's greyed out: tap it once, then App info ⋮ > Allow restricted settings";

    @Override
    public void onListenerConnected() {
        try {
            requestUnbind();
            MusicApps.accessGranted();
        } catch (Throwable t) {
            Log.w(TAG, "Waze media access", t);
        }
    }

    static boolean allowed(Context c) {
        try {
            return c.getSystemService(NotificationManager.class)
                    .isNotificationListenerAccessGranted(new ComponentName(c, MediaAccess.class));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether this install declares the listener (not a root mount install). */
    static boolean declared(Context c) {
        try {
            c.getPackageManager().getServiceInfo(new ComponentName(c, MediaAccess.class), 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * [label] refused Waze's media browser. Without notification access: ask for it once ("Not now" never asks
     * again), then a short note on each pick; with it, the app shares its music with no other app.
     */
    static void refused(String label) {
        try {
            Context c = Customization.appContext();
            if (c == null) return;
            if (allowed(c) || !declared(c)) {
                toast(c, label + " doesn't share its music with Waze");
                return;
            }
            SharedPreferences prefs = c.getSharedPreferences("ungoogled_waze", Context.MODE_PRIVATE);
            Activity a = Customization.activity();
            if (prefs.getBoolean(KEY_ASKED, false) || a == null || a.isFinishing() || a.isDestroyed()) {
                toast(c, label + " needs notification access for Waze");
                return;
            }
            String ask = label + " shares its music only with apps that have notification access. "
                    + "Allow it for Waze? Waze reads no notifications.";
            AlertDialog.Builder b = new AlertDialog.Builder(a)
                    .setMessage(restricted(c) ? ask + "\n\n" + GREYED : ask)
                    .setPositiveButton("Allow", (x, w) -> {
                        prefs.edit().putBoolean(KEY_ASKED, true).apply();
                        back(a);
                        open(a);
                    })
                    .setNegativeButton("Not now", (x, w) -> prefs.edit().putBoolean(KEY_ASKED, true).apply())
                    .setOnCancelListener(x -> prefs.edit().putBoolean(KEY_ASKED, true).apply());
            if (restricted(c)) b.setNeutralButton("App info", (x, w) -> {
                try {
                    a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", a.getPackageName(), null)));
                } catch (Throwable ignored) {
                }
            });
            AlertDialog d = b.create();
            d.setCanceledOnTouchOutside(false);
            d.show();
            Log.i(TAG, "Waze media access: asked for " + label);
        } catch (Throwable t) {
            Log.w(TAG, "Waze media access", t);
        }
    }

    /**
     * Back in Waze from Android's page with access allowed: pick the app again. The listener cannot tell: once it
     * unbound itself Android keeps it snoozed, so allowing access again never connects it.
     */
    private static void back(Activity a) {
        a.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity x) {
                a.unregisterActivityLifecycleCallbacks(this);
                if (allowed(a)) MusicApps.accessGranted();
            }
            @Override public void onActivityCreated(Activity x, android.os.Bundle s) {}
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityPaused(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, android.os.Bundle s) {}
            @Override public void onActivityDestroyed(Activity x) {}
        });
    }

    /** Android 13+ greys out notification access of an app installed from a file until App info allows it. */
    private static boolean restricted(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            int from = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getPackageSource();
            return from == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || from == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    || from == PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Android's notification access page for this listener, else the page listing every app's. */
    private static void open(Activity a) {
        ComponentName listener = new ComponentName(a, MediaAccess.class);
        try {
            a.startActivity(new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", listener.flattenToString()));
        } catch (Throwable t) {
            try {
                a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }
}
