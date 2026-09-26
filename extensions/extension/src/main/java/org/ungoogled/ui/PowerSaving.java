package org.ungoogled.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

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
 * This stands in for SystemUI. While Maps says min mode is armed, the power button
 * opens MinModeActivity, which wakes the screen over the lock screen and keeps it
 * on. Pressing power again while it is showing turns the screen off as usual, and
 * tapping it goes back to normal navigation (Maps' own handler).
 *
 * The press is caught as a Maps window losing focus while the device is going to
 * sleep: the lock screen takes focus ~0.2 s after the press, while the navigation
 * screen is still visible, which is the last moment Android lets an app open a
 * screen of its own. The screen-off broadcast arrives ~0.6 s later, once the window
 * is hidden, and Android 14+ blocks a start from there as a background activity
 * launch (measured on a Samsung, Android 16: "Background activity launch blocked!").
 * The broadcast stays as a fallback for phones that still allow it, and for a phone
 * with no lock screen, where nothing takes focus.
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
    /** Windows already carrying a FocusLost; weak, so a closed window is not kept alive. */
    private static final Map<View, Boolean> watched = new WeakHashMap<>();
    /** When MinModeActivity was last opened: one press reaches it by both routes. */
    private static long openedAt = -10_000;

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
            // The navigation screen is already up when Maps arms, so Front has not seen it.
            if (on) new Handler(Looper.getMainLooper()).post(new WatchWindows());
        } catch (Throwable ignored) {}
    }

    static final class WatchWindows implements Runnable {
        @Override
        public void run() {
            for (View root : new ArrayList<>(Shapes.rootViews())) watch(root);
        }
    }

    static void watch(View root) {
        if (root == null) return;
        synchronized (watched) {
            if (watched.put(root, Boolean.TRUE) != null) return;
        }
        root.getViewTreeObserver().addOnWindowFocusChangeListener(new FocusLost(root));
    }

    /** The power button, seen from a Maps window: focus lost while the device goes to sleep. */
    static final class FocusLost implements ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<View> root;

        FocusLost(View root) { this.root = new WeakReference<>(root); }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus || !armed || minModeLast) return;
            View v = root.get();
            if (v == null || isMinModeWindow(v)) return;
            try {
                Context c = v.getContext().getApplicationContext();
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                // Still awake: a dialog or the notification shade took focus, not the power button.
                if (pm == null || pm.isInteractive()) return;
                Display d = v.getDisplay();
                open(c, d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId());
            } catch (Throwable ignored) {}
        }
    }

    static final class ScreenOff extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            // Power pressed while the power saving screen itself was up: let it go dark.
            if (!armed || minModeLast) return;
            open(c, -1);
        }
    }

    /** Opens MinModeActivity on the given display, or wherever Android puts it for -1. */
    static void open(Context c, int displayId) {
        long now = SystemClock.uptimeMillis();
        if (now - openedAt < 2000) return;
        openedAt = now;
        try {
            Intent i = new Intent(START_MINMODE)
                    .setClassName(c.getPackageName(), MIN_MODE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (displayId < 0) c.startActivity(i);
            else c.startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        } catch (Throwable ignored) {}
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

    /**
     * Remembers whether MinModeActivity or another Maps screen came to the front
     * last, and puts a FocusLost on every other Maps screen that does.
     */
    static final class Front implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            minModeLast = MIN_MODE.equals(a.getClass().getName());
            if (!minModeLast) watch(a.getWindow().getDecorView());
        }
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
