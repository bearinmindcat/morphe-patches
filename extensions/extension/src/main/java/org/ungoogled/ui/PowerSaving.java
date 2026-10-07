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
 * com.android.systemui.minmode.minmodeprovider), and SystemUI answers the next
 * power press by starting Maps' own MinModeActivity over the lock screen. Other
 * phones have no such SystemUI: the provider calls fail silently and the power
 * button just turns the screen off.
 *
 * A phone whose SystemUI has min mode built in (nativeMinMode: the same
 * config_minmode_enabled check Maps makes) always keeps its own, and this class
 * stays out of the way there. The Customization switch "Power saving mode"
 * (allPhones) decides the rest: on, Maps offers the setting whatever
 * Google's server flag and the phone say (forceAvailable) and, on a phone without
 * built-in min mode, this class does SystemUI's part. It defaults to on, except
 * on a phone with built-in min mode, which stays exactly as Google ships it.
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

    public static final String KEY_ALL_PHONES = "power_saving_all_phones";
    private static volatile Boolean nativeMinMode;

    /** SystemUI has min mode built in (a Pixel): Maps' own device check, abmz.a(). Read once. */
    public static boolean nativeMinMode(Context c) {
        Boolean known = nativeMinMode;
        if (known != null) return known;
        boolean has = false;
        try {
            android.content.res.Resources res = c.createPackageContext("com.android.systemui", 0).getResources();
            int id = res.getIdentifier("config_minmode_enabled", "bool", "com.android.systemui");
            has = id != 0 && res.getBoolean(id);
        } catch (Throwable ignored) {}
        nativeMinMode = has;
        return has;
    }

    /** The Customization switch. Unset, it is on everywhere but on a phone with built-in min mode. */
    public static boolean allPhones(Context c) {
        return Shapes.powerSavingPatched()
                && c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALL_PHONES, !nativeMinMode(c));
    }

    public static void setAllPhones(Context c, boolean on) {
        c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALL_PHONES, on).commit();
    }

    /**
     * Asked first by Maps' availability check (abvx.z): true makes Maps offer the
     * setting and arm min mode on this phone; false leaves Google's own answer,
     * which is a server flag AND nativeMinMode.
     */
    public static boolean forceAvailable() {
        Context app = application();
        return app != null && allPhones(app);
    }

    /** This class does SystemUI's part: switched on, and SystemUI cannot do it itself. */
    static boolean standIn(Context c) {
        return allPhones(c) && !nativeMinMode(c);
    }

    /**
     * aghj.O: Maps arms min mode when navigation starts with Power saving mode on,
     * and disarms it when navigation ends, the setting is turned off, or the app
     * goes into a split-screen or freeform window. On a phone with built-in min
     * mode, SystemUI has just been armed by Maps itself and nothing happens here.
     */
    public static void armed(boolean on) {
        try {
            Context app = application();
            if (app == null) return;
            armed = on && standIn(app);
            synchronized (PowerSaving.class) {
                if (armed) trackLifecycle(app);
                if (armed && screenOff == null) {
                    screenOff = new ScreenOff();
                    // SCREEN_OFF is a protected system broadcast: no exported flag needed.
                    app.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                } else if (!armed && screenOff != null) {
                    try { app.unregisterReceiver(screenOff); } catch (Throwable ignored) {}
                    screenOff = null;
                }
            }
            // The navigation screen is already up when Maps arms, so Front has not seen it.
            if (armed) new Handler(Looper.getMainLooper()).post(new WatchWindows());
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
     * Where SystemUI opened it itself, it gets nothing from here: SystemUI looks
     * after the screen on those phones.
     */
    public static void onMinModeCreate(Activity a) {
        try {
            // Recorded on every phone: the navigation zoom tiles skip this window on a Pixel too.
            minModeWindow = new WeakReference<>(a.getWindow().getDecorView());
            if (!standIn(a)) return;
            a.setShowWhenLocked(true);
            a.setTurnScreenOn(true);
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
     * last, and puts a FocusLost on every other Maps screen that does. Also keeps the
     * speedometer to the power saving screen's own lifetime, and restarts the idle
     * countdown whenever a Maps screen comes back.
     */
    static final class Front implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            minModeLast = MIN_MODE.equals(a.getClass().getName());
            if (!minModeLast) {
                watch(a.getWindow().getDecorView());
                front = new WeakReference<>(a);
                lastTouch = SystemClock.uptimeMillis();
            }
        }
        @Override public void onActivityStarted(Activity a) {
            if (MIN_MODE.equals(a.getClass().getName())) Speedometer.start(a);
        }
        @Override public void onActivityStopped(Activity a) {
            if (MIN_MODE.equals(a.getClass().getName())) Speedometer.stop(a);
        }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }

    // ---- Power Saving Options -----------------------------------------------------
    // Customization > Power Saving Options. Everything here is off until switched on.

    public static final String KEY_UNLOCKED = "power_saving_unlocked";
    public static final String KEY_SPEEDOMETER = "power_saving_speedometer";
    public static final String KEY_IDLE = "power_saving_idle_seconds";
    public static final String KEY_LOW_FPS = "power_saving_low_fps";
    public static final String KEY_THEME = "power_saving_theme_mode";

    /** Lower frame rate and Power saving theme: off, while navigating, everywhere. */
    public static final int LOW_FPS_OFF = 0, LOW_FPS_NAV = 1, LOW_FPS_ALL = 2;
    public static final int THEME_OFF = 0, THEME_NAV = 1, THEME_ALL = 2;
    /** The power saving screen's own rates: MinModeActivity.onStart asks for 15 fps and a 30 Hz window. */
    static final int SAVER_FPS = 15;
    static final float SAVER_HZ = 30f;
    /** Auto-switch when idle: the choices, in seconds; 0 is off. */
    public static final int[] IDLE_CHOICES = {0, 15, 30, 60, 120};

    static volatile boolean UNLOCKED, SPEEDOMETER;
    static volatile int IDLE_SECONDS, LOW_FPS, THEME;
    private static volatile boolean loaded;

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
    }

    /** Read again at every Activity attach (Shapes.wrap), and lazily by the hooks in Maps. */
    public static void refresh(Context c) {
        if (!Shapes.powerSavingPatched()) return;
        android.content.SharedPreferences p = prefs(c);
        UNLOCKED = p.getBoolean(KEY_UNLOCKED, false);
        SPEEDOMETER = p.getBoolean(KEY_SPEEDOMETER, false);
        IDLE_SECONDS = Math.max(0, p.getInt(KEY_IDLE, 0));
        LOW_FPS = p.getInt(KEY_LOW_FPS, LOW_FPS_OFF);
        THEME = p.getInt(KEY_THEME, THEME_OFF);
        loaded = true;
        if (SPEEDOMETER) trackLifecycle(c);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        Context app = application();
        if (app != null) refresh(app);
    }

    public static boolean unlocked(Context c) { refresh(c); return UNLOCKED; }
    public static boolean speedometer(Context c) { refresh(c); return SPEEDOMETER; }
    public static int idleSeconds(Context c) { refresh(c); return IDLE_SECONDS; }
    public static int lowFps(Context c) { refresh(c); return LOW_FPS; }
    public static int theme(Context c) { refresh(c); return THEME; }

    public static void setUnlocked(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_UNLOCKED, on).commit(); refresh(c); }
    public static void setSpeedometer(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_SPEEDOMETER, on).commit(); refresh(c); }
    public static void setIdleSeconds(Context c, int s) { prefs(c).edit().putInt(KEY_IDLE, s).commit(); refresh(c); }
    public static void setLowFps(Context c, int mode) { prefs(c).edit().putInt(KEY_LOW_FPS, mode).commit(); refresh(c); }
    public static void setTheme(Context c, int mode) { prefs(c).edit().putInt(KEY_THEME, mode).commit(); refresh(c); }

    /** Lower frame rate set to everywhere: the main window's 60 Hz becomes 30 (RefreshRate.window). */
    static boolean lowFpsEverywhere() {
        ensureLoaded();
        return LOW_FPS == LOW_FPS_ALL;
    }

    /** The map's frame-rate ceiling right now (RefreshRate), 0 for none. */
    static int fpsCap() {
        ensureLoaded();
        int mode = LOW_FPS;
        if (mode == LOW_FPS_ALL || (mode == LOW_FPS_NAV && guidance())) return SAVER_FPS;
        return 0;
    }

    /** Turn-by-turn is live: the navigation camera hook ran in the last three seconds. */
    static boolean guidance() {
        return SystemClock.uptimeMillis() - Shapes.navCamAt < 3000L;
    }

    // ---- the power saving map, in navigation or everywhere ----

    /**
     * bjei.b(), the map's base style: Maps' own "in min mode" flag, read when it picks
     * between its styles, with its "driving navigation" flag next to it. True picks the
     * power saving screen's black styles (NAVIGATION_MIN_MODE_AUTO while driving,
     * NAVIGATION_MIN_MODE otherwise). That style is roads only -- no place or street
     * names -- which is why it can be limited to navigation, where the turn card and
     * the route's own labels name the streets.
     */
    public static boolean minModeStyle(boolean inMinMode, boolean driving) {
        if (inMinMode) return true;
        ensureLoaded();
        int mode = THEME;
        return mode == THEME_ALL || (mode == THEME_NAV && driving);
    }

    // ---- opening the power saving screen without locking the phone ----

    /** The most recent Maps screen to come to the front, other than the power saving screen. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** The last time a finger went down on the navigation screen, or it came back to the front. */
    static volatile long lastTouch = SystemClock.uptimeMillis();
    private static boolean guidanceWas;

    /** Open without locking: a button in navigation. */
    static boolean navButton() {
        return Shapes.powerSavingPatched() && UNLOCKED;
    }

    /**
     * Opens the power saving screen while the phone stays unlocked; tapping it goes back
     * (Maps' own handler). FLAG_ACTIVITY_NO_USER_ACTION matters: the screen has a task
     * of its own, and without it Android counts the switch as the user leaving Maps, so
     * the navigation screen drops into picture-in-picture -- a small live window over
     * the black screen, and a tap that then goes to the home screen (measured).
     */
    static void openUnlocked(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - openedAt < 2000) return;
        openedAt = now;
        try {
            c.startActivity(new Intent(START_MINMODE)
                    .setClassName(c.getPackageName(), MIN_MODE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (Throwable ignored) {}
    }

    static final class OpenClick implements View.OnClickListener {
        @Override public void onClick(View v) { openUnlocked(v.getContext()); }
    }

    /**
     * From the navigation tiles' ticker, several times a second while navigating: Lower
     * frame rate's start and end of navigation, and Auto-switch when idle.
     *
     * @param decor the navigation screen's window, null when it is not showing
     */
    static void navTick(android.view.ViewGroup decor, boolean navUp) {
        boolean live = guidance();
        if (live != guidanceWas) {
            guidanceWas = live;
            if (live) {
                lastTouch = SystemClock.uptimeMillis();
                Context app = application();
                if (app != null) trackLifecycle(app);
            }
            if (LOW_FPS == LOW_FPS_NAV) RefreshRate.navChanged();
        }
        if (LOW_FPS == LOW_FPS_NAV) {
            // the navigation screen's own window: the Maps screen in front whose window it is
            Activity a = front.get();
            if (!live) RefreshRate.navWindow(null, false);
            else if (navUp && decor != null && a != null && a.getWindow().getDecorView() == decor) RefreshRate.navWindow(a, true);
        }
        int idle = IDLE_SECONDS;
        if (!navUp || decor == null || idle <= 0 || !Shapes.powerSavingPatched()) {
            TouchSpy.remove();
            return;
        }
        TouchSpy.attach(decor);
        long now = SystemClock.uptimeMillis();
        if (now - lastTouch < idle * 1000L || minModeLast) return;
        try {
            // Not while something else has the screen: a dialog or menu has the focus, the
            // keyboard is up, or Maps shares the screen with another app.
            if (!decor.hasWindowFocus() || keyboardUp(decor)) return;
            Activity a = front.get();
            if (a != null && a.isInMultiWindowMode()) return;
            PowerManager pm = (PowerManager) decor.getContext().getSystemService(Context.POWER_SERVICE);
            if (pm == null || !pm.isInteractive()) return;
        } catch (Throwable t) { return; }
        lastTouch = now;
        openUnlocked(decor.getContext());
    }

    private static boolean keyboardUp(View decor) {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        android.view.WindowInsets in = decor.getRootWindowInsets();
        return in != null && in.isVisible(android.view.WindowInsets.Type.ime());
    }

    /**
     * Auto-switch when idle: sees each finger that goes down anywhere on the navigation
     * screen, and lets it through. It sits on top of the window and answers "not mine"
     * to the first touch, so Android offers that touch to the views underneath as usual
     * and the rest of the gesture never comes here.
     */
    static final class TouchSpy extends View {
        private static WeakReference<TouchSpy> current = new WeakReference<>(null);

        TouchSpy(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false);
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            lastTouch = SystemClock.uptimeMillis();
            return false;
        }

        static void attach(android.view.ViewGroup decor) {
            TouchSpy s = current.get();
            if (s != null && s.getParent() == decor) {
                if (decor.getChildAt(decor.getChildCount() - 1) != s) s.bringToFront();
                return;
            }
            remove();
            s = new TouchSpy(decor.getContext());
            decor.addView(s, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            current = new WeakReference<>(s);
        }

        static void remove() {
            TouchSpy s = current.get();
            current = new WeakReference<>(null);
            if (s == null) return;
            android.view.ViewParent p = s.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(s);
        }
    }

    // ---- the speedometer on the power saving screen ----

    /** The speed limit Maps' own speedometer shows (-1: none) and when Maps last said so. */
    private static volatile int limit = -1;
    private static volatile long limitAt = -100_000;
    private static volatile Boolean limitInMiles;

    /**
     * avmi.E, the speedometer's speed-limit setter: the limit in the units Maps shows
     * it in, -1 for none. Called for every location update while navigating, so a
     * value that stops coming in is a stale one.
     */
    public static void speedLimit(int value, Object units) {
        limit = value;
        limitAt = SystemClock.uptimeMillis();
        if (units != null) {
            // a protobuf enum, whose toString() is its number: 1 = MILES, 3 = MILES_YARDS
            String n = String.valueOf(units);
            limitInMiles = "1".equals(n) || "3".equals(n);
        }
        Speedometer.refresh();
    }

    /** Miles per hour or km/h: Maps' own units for the limit, else its badge's label, else the country. */
    static boolean miles() {
        Boolean m = limitInMiles;
        if (m != null) return m;
        android.widget.TextView unit = Shapes.speedUnit;
        if (unit != null) {
            CharSequence t = unit.getText();
            if (t != null) return "mph".equalsIgnoreCase(t.toString().trim());
        }
        // Maps' own rule for a road's units (ahit.b): these use miles.
        String cc = java.util.Locale.getDefault().getCountry();
        return "US".equals(cc) || "GB".equals(cc) || "LR".equals(cc) || "MM".equals(cc);
    }

    /** US-style (and Canadian) rectangular limit signs; the round red ring everywhere else. */
    static boolean rectangularSign() {
        String cc = java.util.Locale.getDefault().getCountry();
        return "US".equals(cc) || "CA".equals(cc) || "LR".equals(cc);
    }

    /**
     * Speedometer: the current speed and the speed limit, drawn at the bottom left of
     * the power saving screen, where Maps puts its own speedometer in navigation. The
     * speed comes from the GPS, which is on anyway while navigating; the limit is the
     * one Maps' speedometer shows. White on black like the rest of that screen, inside
     * its BurnInLayout so it moves with everything else to spare the display.
     */
    static final class Speedometer extends View implements android.location.LocationListener {
        private static WeakReference<Speedometer> shown = new WeakReference<>(null);
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private volatile float speed = -1f;      // m/s, -1 = not known
        private volatile long speedAt;
        private android.location.Location last;

        Speedometer(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        static void start(Activity a) {
            if (!SPEEDOMETER || shown.get() != null) return;
            try {
                android.view.ViewGroup host = burnInLayout(a.getWindow().getDecorView());
                if (host == null) host = a.findViewById(android.R.id.content);
                if (host == null) return;
                Speedometer s = new Speedometer(a);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.START);
                lp.leftMargin = Math.round(16 * s.d);
                lp.bottomMargin = Math.round(112 * s.d);
                // Under the screen's own tap catcher, its last child: a tap anywhere still goes back.
                int at = Math.max(0, host.getChildCount() - 1);
                host.addView(s, at, lp);
                shown = new WeakReference<>(s);
                android.location.LocationManager lm =
                        (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, s,
                            android.os.Looper.getMainLooper());
                }
                s.postDelayed(new Tick(s), 1000);
            } catch (Throwable ignored) {}
        }

        static void stop(Activity a) {
            Speedometer s = shown.get();
            shown = new WeakReference<>(null);
            if (s == null) return;
            try {
                android.location.LocationManager lm =
                        (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) lm.removeUpdates(s);
            } catch (Throwable ignored) {}
            android.view.ViewParent p = s.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(s);
        }

        static void refresh() {
            Speedometer s = shown.get();
            if (s != null) s.postInvalidate();
        }

        /** Redraws once a second, so a speed that stops coming in turns into "--". */
        static final class Tick implements Runnable {
            private final WeakReference<Speedometer> s;
            Tick(Speedometer s) { this.s = new WeakReference<>(s); }
            @Override public void run() {
                Speedometer v = s.get();
                if (v == null || v.getParent() == null) return;
                v.invalidate();
                v.postDelayed(this, 1000);
            }
        }

        private static android.view.ViewGroup burnInLayout(View v) {
            if (v instanceof android.view.ViewGroup) {
                if (v.getClass().getName().endsWith("BurnInLayout")) return (android.view.ViewGroup) v;
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.ViewGroup r = burnInLayout(g.getChildAt(i));
                    if (r != null) return r;
                }
            }
            return null;
        }

        @Override public void onLocationChanged(android.location.Location l) {
            float v = -1f;
            if (l.hasSpeed()) {
                v = l.getSpeed();
            } else if (last != null && l.getTime() > last.getTime()) {
                v = last.distanceTo(l) * 1000f / (l.getTime() - last.getTime());
            }
            last = l;
            speed = v;
            speedAt = SystemClock.uptimeMillis();
            invalidate();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}

        private static final float BOX_DP = 64f, GAP_DP = 10f;

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round((2 * BOX_DP + GAP_DP) * d), Math.round(BOX_DP * d));
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            long now = SystemClock.uptimeMillis();
            boolean mph = miles();
            float v = speed;
            boolean known = v >= 0 && now - speedAt < 4000;
            int shownSpeed = known ? Math.round(v * (mph ? 2.2369363f : 3.6f)) : -1;
            int lim = now - limitAt < 15000 ? limit : -1;
            float box = BOX_DP * d;

            // the speed: a dark rounded square, red figures above the limit, as on Maps' own badge
            android.graphics.RectF r = new android.graphics.RectF(d, d, box - d, box - d);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xFF000000);
            c.drawRoundRect(r, 12 * d, 12 * d, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(0xFF5F6368);
            c.drawRoundRect(r, 12 * d, 12 * d, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(26 * d);
            paint.setColor(lim > 0 && shownSpeed > lim ? 0xFFF28B82 : 0xFFFFFFFF);
            c.drawText(shownSpeed >= 0 ? String.valueOf(shownSpeed) : "--", box / 2, box / 2 + 6 * d, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            paint.setTextSize(12 * d);
            paint.setColor(0xFFBDC1C6);
            c.drawText(mph ? "mph" : "km/h", box / 2, box - 12 * d, paint);

            if (lim <= 0) return;
            float x0 = box + GAP_DP * d;
            if (rectangularSign()) {
                android.graphics.RectF s = new android.graphics.RectF(x0 + 6 * d, d, x0 + box - 6 * d, box - d);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f * d);
                paint.setColor(0xFFFFFFFF);
                c.drawRoundRect(s, 6 * d, 6 * d, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setTextSize(9 * d);
                c.drawText("LIMIT", x0 + box / 2, 18 * d, paint);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(24 * d);
                c.drawText(String.valueOf(lim), x0 + box / 2, box - 14 * d, paint);
            } else {
                float cx = x0 + box / 2, cy = box / 2, rad = box / 2 - 4 * d;
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(6 * d);
                paint.setColor(0xFFE53935);
                c.drawCircle(cx, cy, rad, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize((lim >= 100 ? 20 : 24) * d);
                c.drawText(String.valueOf(lim), cx, cy + 8 * d, paint);
            }
        }
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
