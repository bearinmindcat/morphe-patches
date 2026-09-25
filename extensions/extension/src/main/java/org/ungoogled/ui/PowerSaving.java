package org.ungoogled.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * Power saving mode ("min mode") on every phone, not only Pixels.
 *
 * On a Pixel, while navigating with Power saving mode on, Maps hands the power
 * button to SystemUI (aghj.O(true): "minModeOn" plus a binder, written into
 * com.android.systemui.minmode.minmodeprovider). On the next power press SystemUI
 * puts the display into its low-power min mode and starts Maps' own
 * MinModeActivity over the lock screen. Other phones have no such SystemUI: the
 * provider calls fail silently and the power button just turns the screen off.
 *
 * This stands in for SystemUI. While Maps says min mode is armed, the screen
 * turning off opens MinModeActivity, which wakes the screen over the lock screen
 * and keeps it on. Pressing power again while it is showing turns the screen off
 * as usual, and tapping it goes back to normal navigation (Maps' own handler).
 */
public final class PowerSaving {
    static final String MIN_MODE = "com.google.android.apps.gmm.features.minmode.MinModeActivity";
    static final String START_MINMODE = "com.android.systemui.action.START_MINMODE";

    private static volatile boolean armed;
    private static BroadcastReceiver screenOff;
    /** The last Maps Activity to come to the front was MinModeActivity. */
    private static volatile boolean minModeLast;
    private static boolean lifecycleTracked;
    private static WeakReference<View> minModeWindow = new WeakReference<>(null);

    private PowerSaving() {}

    /**
     * aghj.O: Maps arms min mode when navigation starts with Power saving mode on,
     * and disarms it when navigation ends, the setting is turned off, or the app
     * goes into a split-screen or freeform window.
     */
    public static void armed(boolean on) {
        armed = on;
        try {
            Context app = application();
            if (app == null) return;
            synchronized (PowerSaving.class) {
                trackLifecycle(app);
                if (on && screenOff == null) {
                    screenOff = new ScreenOff();
                    // SCREEN_OFF is a protected system broadcast: no exported flag needed.
                    app.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                } else if (!on && screenOff != null) {
                    try { app.unregisterReceiver(screenOff); } catch (Throwable ignored) {}
                    screenOff = null;
                }
            }
        } catch (Throwable ignored) {}
    }

    static final class ScreenOff extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            // Power pressed while the power saving screen itself was up: let it go dark.
            if (!armed || minModeLast) return;
            try {
                c.startActivity(new Intent(START_MINMODE)
                        .setClassName(c.getPackageName(), MIN_MODE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Start of MinModeActivity.onCreate: what Pixel's SystemUI does for it --
     * over the lock screen, screen woken on launch and kept on while it shows.
     */
    public static void onMinModeCreate(Activity a) {
        try {
            a.setShowWhenLocked(true);
            a.setTurnScreenOn(true);
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            minModeWindow = new WeakReference<>(a.getWindow().getDecorView());
            trackLifecycle(a.getApplicationContext());
            minModeLast = true;
        } catch (Throwable ignored) {}
    }

    /** The power saving screen's window, which the navigation zoom tiles must not attach to. */
    static boolean isMinModeWindow(View root) {
        return root != null && root == minModeWindow.get();
    }

    private static void trackLifecycle(Context c) {
        synchronized (PowerSaving.class) {
            if (lifecycleTracked) return;
            Context app = c.getApplicationContext();
            if (!(app instanceof Application)) return;
            ((Application) app).registerActivityLifecycleCallbacks(new Front());
            lifecycleTracked = true;
        }
    }

    /** Remembers whether MinModeActivity or another Maps screen came to the front last. */
    static final class Front implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) { minModeLast = MIN_MODE.equals(a.getClass().getName()); }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
