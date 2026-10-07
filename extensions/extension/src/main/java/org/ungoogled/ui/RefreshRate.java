package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * "120 Hz": Maps holds itself to 60 Hz twice over. Its main Activity asks for a
 * 60 Hz window (nce.onStart: preferredRefreshRate = 60, Google's "intended
 * behavior"), and the map renderer's frame limiter targets 30 fps, or 60 where a
 * flag allows (bjmr.h, fed by bmwo.e, navigation and others). Phones that Maps'
 * servers put on its newer map renderer (GeoXP mapcore, flag ENABLE_GEOXP_MAPCORE)
 * get the same target as a field of that renderer's settings instead of through
 * bjmr.h, so the target is mapped where both get it: bktq.b, the map's frame-rate
 * controller (issue #21: navigation asks for 30, and on that renderer the car and
 * camera moved at 30 fps). Navigation also turns on adaptive frame rate, which skips
 * frames while the camera moves slowly, on both renderers; bktq.a is its switch.
 *
 * While the switch is on, the window asks for no rate at all, so the phone treats
 * Maps like any other app (up to its fastest rate while things move), and the map
 * may draw as fast as the screen refreshes, every frame. Maps' own deliberately low
 * rates stay: power saving mode's 15 fps and 30 Hz window, and the 10 fps states.
 *
 * Power Saving Options > Lower frame rate goes the other way: the power saving
 * screen's own 15 fps and 30 Hz, while navigating or everywhere. It wins over
 * 120 Hz wherever it applies.
 */
public final class RefreshRate {
    private static volatile float screenMax;
    /** The frame-rate controller (bktq) and the last target Maps gave it, to apply it again. */
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile int asked;
    /** The navigation window's own rate, put back on that same window when navigation ends. */
    private static float navWindowWas = -1f;
    private static WeakReference<Activity> navWindowOf = new WeakReference<>(null);

    private RefreshRate() {}

    /** In place of the rate Maps' main window asks for: nothing instead of its 60 Hz cap. */
    public static float window(float requested) {
        if (requested != 60f) return requested;
        if (PowerSaving.lowFpsEverywhere()) return PowerSaving.SAVER_HZ;
        return Shapes.HIGH_REFRESH ? 0f : requested;
    }

    /** bjmr.h: the map's target frame rate, 0 meaning Maps' default of 30. */
    public static long map(long fps) {
        if (Shapes.HIGH_REFRESH && (fps == 0 || fps >= 30)) fps = Math.max(fps, Math.round(screenMax()));
        return cap(fps);
    }

    /**
     * bktq.b: the target as the frame-rate controller gets it, for either renderer, 0
     * meaning the renderer's default (30 on the old one, 60 on the newer one).
     */
    public static int map(Object controller, int fps) {
        if (controller != null) {
            if (RefreshRate.controller.get() != controller) RefreshRate.controller = new WeakReference<>(controller);
            asked = fps;
        }
        if (Shapes.HIGH_REFRESH && (fps == 0 || fps >= 30)) fps = Math.max(fps, Math.round(screenMax()));
        return (int) cap(fps);
    }

    /** Lower frame rate: no faster than the power saving screen where it applies; 0 = default, capped too. */
    private static long cap(long fps) {
        int cap = PowerSaving.fpsCap();
        return cap > 0 && (fps == 0 || fps > cap) ? cap : fps;
    }

    /**
     * bktq.a: Maps' "adaptive frame rate", which navigation turns on. Unless a finger is
     * on the map, a frame is drawn only once the picture has moved about 1% of the
     * screen, or at a slower minimum rate, so the car and the camera's turns move in
     * uneven jumps (issue #21). Off while the switch is on.
     */
    public static boolean adaptive(boolean on) {
        return on && !Shapes.HIGH_REFRESH;
    }

    /**
     * Navigation started or ended while Lower frame rate is set to navigation only: the
     * map takes Maps' last target again, through the cap.
     */
    static void navChanged() {
        Object c = controller.get();
        if (c == null) return;
        try { c.getClass().getMethod("uaTarget", int.class).invoke(c, asked); }
        catch (Throwable ignored) {}
    }

    /**
     * Lower frame rate set to navigation only, every navigation tick: the navigation
     * window asks for 30 Hz while navigating, and for what it asked before once it
     * stops. Held rather than set once, so nothing that happens around the start of
     * navigation can leave it at Maps' own rate.
     */
    static void navWindow(Activity nav, boolean navigating) {
        try {
            if (!navigating || navWindowOf.get() != nav) restoreNavWindow();
            if (!navigating || nav == null) return;
            Window w = nav.getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp.preferredRefreshRate == PowerSaving.SAVER_HZ) return;
            if (navWindowWas < 0) {
                navWindowWas = lp.preferredRefreshRate;
                navWindowOf = new WeakReference<>(nav);
            }
            lp.preferredRefreshRate = PowerSaving.SAVER_HZ;
            w.setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private static void restoreNavWindow() {
        Activity was = navWindowOf.get();
        float rate = navWindowWas;
        navWindowWas = -1f;
        navWindowOf = new WeakReference<>(null);
        if (rate < 0 || was == null) return;
        Window w = was.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.preferredRefreshRate = rate;
        w.setAttributes(lp);
    }

    /** The fastest refresh rate the main screen offers. */
    static float screenMax() {
        float max = screenMax;
        if (max > 0) return max;
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            Display d = ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
        } catch (Throwable ignored) {}
        if (max <= 0) return 60f;   // not known yet: asked again next time
        screenMax = max;
        return max;
    }
}
