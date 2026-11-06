package org.ungoogled.samsungonehand;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.provider.Settings;

import java.lang.ref.WeakReference;

/**
 * Asks for the accessibility service from One Hand Operation +'s main screen, while its switch is on and the service
 * is off: Android has no prompt for it, only its settings page. A sideloaded app's accessibility switch is greyed
 * out (Android 13+ restricted settings) until it is tapped once and App info's menu allows it, so that is said too,
 * and back from Accessibility without the service App info is offered first.
 */
public final class Access {
    private static final String ASK = "Turn on One Hand Operation + in Accessibility so its gestures can go back, home and more.";
    private static final String GREYED = "If it's greyed out: tap it once, then App info ⋮ > Allow restricted settings";

    /** Where the user was sent last: nowhere yet, Accessibility, App info. */
    private static final int NONE = 0, ACCESS = 1, APP_INFO = 2;

    private static WeakReference<Activity> screen = new WeakReference<>(null);
    private static SharedPreferences.OnSharedPreferenceChangeListener watching;
    private static boolean notNow;
    private static boolean asking;
    private static int back = NONE;

    private Access() {
    }

    /** The app's main screen came up: ask when its switch is on without the service; watch the switch from here. */
    @SuppressWarnings("deprecation")
    public static void resumed(Activity a) {
        try {
            OneHand.context(a);
            screen = new WeakReference<>(a);
            if (watching == null) {
                watching = (prefs, key) -> {
                    if (!"sidegesturepad_enabled".equals(key)) return;
                    Activity on = screen.get();
                    if (on != null && prefs.getBoolean(key, false)) {
                        notNow = false;
                        back = NONE;
                        OneHand.MAIN.postDelayed(() -> check(on), 600);
                    }
                };
                a.getApplicationContext().getSharedPreferences("sidegesturepad_prefs", Context.MODE_MULTI_PROCESS)
                        .registerOnSharedPreferenceChangeListener(watching);
            }
            int from = back;
            back = NONE;
            OneHand.MAIN.postDelayed(() -> check(a, from), 400);
        } catch (RuntimeException e) {
            OneHand.debug("access check: " + e);
        }
    }

    private static void check(Activity a) {
        check(a, NONE);
    }

    private static void check(Activity a, int from) {
        if (a.isFinishing() || a.isDestroyed() || asking) return;
        if (!OneHand.switchedOn(a) || OneHand.serviceEnabled(a)) return;
        if (notNow && from == NONE) return;
        ask(a, from);
    }

    private static void ask(Activity a, int from) {
        boolean restricted = restricted(a);
        String message = !restricted || from == APP_INFO ? ASK : from == ACCESS ? GREYED : ASK + "\n\n" + GREYED;
        boolean night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        AlertDialog.Builder b = new AlertDialog.Builder(a, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        android.content.DialogInterface.OnClickListener access = (d, w) -> {
            back = ACCESS;
            openAccessibility(a);
        };
        android.content.DialogInterface.OnClickListener appInfo = (d, w) -> {
            back = APP_INFO;
            openAppInfo(a);
        };
        b.setMessage(message)
                .setNegativeButton("Not now", (d, w) -> notNow = true)
                .setOnCancelListener(d -> notNow = true)
                .setOnDismissListener(d -> asking = false);
        if (restricted && from == ACCESS) b.setPositiveButton("App info", appInfo).setNeutralButton("Accessibility", access);
        else if (restricted && from == NONE) b.setPositiveButton("Accessibility", access).setNeutralButton("App info", appInfo);
        else b.setPositiveButton("Accessibility", access);
        AlertDialog d = b.create();
        d.setCanceledOnTouchOutside(false);
        asking = true;
        d.show();
        // Gone with the screen (a rotation or theme change recreates it, and it asks again).
        a.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPreDestroyed(Activity x) {
                a.unregisterActivityLifecycleCallbacks(this);
                asking = false;
                if (d.isShowing()) d.dismiss();
            }
            @Override public void onActivityCreated(Activity x, android.os.Bundle s) {}
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityResumed(Activity x) {}
            @Override public void onActivityPaused(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, android.os.Bundle s) {}
            @Override public void onActivityDestroyed(Activity x) {}
        });
    }

    /** Android's page for this service, else the Accessibility list. */
    private static void openAccessibility(Context c) {
        ComponentName service = new ComponentName(c, ActionService.class);
        try {
            c.startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString()));
        } catch (RuntimeException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** App info, whose menu has "Allow restricted settings". */
    private static void openAppInfo(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null)));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Whether Android 13+ "restricted settings" may grey out the accessibility switch: Settings decides by an app-op
     * an app may not read, so this goes by what sets it, an install the package installer marked as from a file or a
     * download. It still says yes after the user allowed restricted settings, hence "if it's greyed out".
     */
    static boolean restricted(Context c) {
        try {
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
            InstallSourceInfo source = c.getPackageManager().getInstallSourceInfo(c.getPackageName());
            int from = source.getPackageSource();
            return from == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || from == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}
