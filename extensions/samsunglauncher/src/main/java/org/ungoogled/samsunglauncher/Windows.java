package org.ungoogled.samsunglauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One UI Home's own windows (edge panel, taskbar, gesture and overlay windows) use window types only the system may
 * use, some of them One UI's own. Here such a window becomes an ordinary overlay where the user has allowed "Display
 * over other apps", and is otherwise skipped quietly; the home screen itself is an ordinary activity and unaffected.
 */
public final class Windows {
    private static final String TAG = "UA";
    private static final Set<View> REFUSED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Windows() {
    }

    /** A window type only the system may use: 2000 and up, but not the ordinary overlay (2038). */
    private static boolean systemType(int type) {
        return type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private static int overlay(Context c, int type) {
        return systemType(type) && Settings.canDrawOverlays(c) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : type;
    }

    /** The display a context belongs to; the default display for a context without one (the application's). */
    private static Display displayOf(Context c) {
        try {
            Display d = c.getDisplay();
            if (d != null) return d;
        } catch (UnsupportedOperationException e) {
            // Not a visual context.
        }
        return c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    /**
     * Context.getDisplay(): One UI answers it for any context, Android only for visual ones; the application context
     * gets the default display here.
     */
    public static Display getDisplay(Context c) {
        return displayOf(c);
    }

    public static Display getDisplay(android.content.ContextWrapper c) {
        return displayOf(c);
    }

    /**
     * Back callbacks: One UI registers some as observers (a negative priority), which Android only allows the system;
     * those are skipped here (the screen still handles back as usual).
     */
    public static void registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher d, int priority,
            android.window.OnBackInvokedCallback callback) {
        try {
            d.registerOnBackInvokedCallback(priority, callback);
        } catch (IllegalArgumentException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "back callback refused: " + e.getMessage());
        }
    }

    public static Context createWindowContext(Context c, int type, Bundle options) {
        return createWindowContext(c, displayOf(c), type, options);
    }

    /** A window context of an allowed type; where Android refuses one, a plain context of that display. */
    public static Context createWindowContext(Context c, Display display, int type, Bundle options) {
        if (display == null) display = displayOf(c);
        int t = overlay(c, type);
        if (!systemType(t)) {
            try {
                return c.createWindowContext(display, t, options);
            } catch (RuntimeException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window context refused: " + type + ": " + e);
            }
        }
        return c.createDisplayContext(display);
    }

    private static void asOverlay(View view, ViewGroup.LayoutParams params) {
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
            lp.type = overlay(view.getContext(), lp.type);
        }
    }

    public static void addView(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        if (!(wm instanceof WindowManager)) {
            wm.addView(view, params);
            return;
        }
        asOverlay(view, params);
        try {
            wm.addView(view, params);
            REFUSED.remove(view);
        } catch (WindowManager.BadTokenException | SecurityException | IllegalArgumentException
                 | UnsupportedOperationException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window refused: " + e.getMessage());
            REFUSED.add(view);
        }
    }

    public static void updateViewLayout(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        if (!(wm instanceof WindowManager)) {
            wm.updateViewLayout(view, params);
            return;
        }
        asOverlay(view, params);
        if (!REFUSED.contains(view)) {
            try {
                wm.updateViewLayout(view, params);
            } catch (IllegalArgumentException e) {
                // Not attached (refused earlier in another way).
            }
        }
    }

    public static void removeView(ViewManager wm, View view) {
        if (!(wm instanceof WindowManager)) {
            wm.removeView(view);
            return;
        }
        if (REFUSED.remove(view)) return;
        try {
            wm.removeView(view);
        } catch (IllegalArgumentException e) {
            // Not attached.
        }
    }

    public static void removeViewImmediate(WindowManager wm, View view) {
        if (REFUSED.remove(view)) return;
        try {
            wm.removeViewImmediate(view);
        } catch (IllegalArgumentException e) {
            // Not attached.
        }
    }
}
