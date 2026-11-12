package org.ungoogled.coloroslauncher;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * The wallpaper image the launcher reads to pick dark or light labels, page dots and status bar icons and to blur
 * behind folders and menus. A system home screen may read it; an installed app is refused with a SecurityException
 * (READ_EXTERNAL_STORAGE on Android 11 and 12, a system permission later). Where the image is refused, the launcher gets
 * one of the wallpaper's own colors instead (WallpaperColors, open to every app), light or dark as Android itself
 * judged the wallpaper, so its choices match the wallpaper.
 */
public final class Wallpapers {
    private static final String TAG = "UA";

    private Wallpapers() {
    }

    /** WallpaperManager.getDrawable(). */
    public static Drawable getDrawable(WallpaperManager wm) {
        try {
            return wm.getDrawable();
        } catch (SecurityException e) {
            return fromColors(wm, WallpaperManager.FLAG_SYSTEM);
        }
    }

    /** WallpaperManager.getDrawable(which), Android 14's; below it the system wallpaper. */
    static Drawable getDrawable(WallpaperManager wm, int which) {
        try {
            if (Build.VERSION.SDK_INT >= 34) return wm.getDrawable(which);
            return wm.getDrawable();
        } catch (SecurityException e) {
            return fromColors(wm, (which & WallpaperManager.FLAG_LOCK) != 0 ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM);
        }
    }

    /** An image of the screen's proportions filled with the wallpaper's main color, as light as Android rates it. */
    static Drawable fromColors(WallpaperManager wm, int which) {
        WallpaperColors colors = null;
        try {
            colors = wm.getWallpaperColors(which);
            if (colors == null && which != WallpaperManager.FLAG_SYSTEM) colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        } catch (RuntimeException e) {
            // No wallpaper service.
        }
        if (colors == null) return null;
        int color = colors.getPrimaryColor().toArgb() | 0xff000000;
        boolean darkText;
        try {
            darkText = (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        } catch (LinkageError e) {
            darkText = luminance(color) >= 196;
        }
        // Brightness as the launcher measures it: at least 196 is a light wallpaper (dark labels and icons).
        int target = darkText ? Color.WHITE : Color.BLACK;
        for (int i = 1; i <= 10 && (darkText ? luminance(color) < 210 : luminance(color) >= 150); i++) {
            color = blend(color, target, 0.15f);
        }
        DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
        int w = Math.max(1, m.widthPixels / 8), h = Math.max(1, m.heightPixels / 8);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "wallpaper from colors: " + Integer.toHexString(color) + " dark text " + darkText);
        return new BitmapDrawable(Resources.getSystem(), bitmap);
    }

    /*
     * The navigation bar's buttons over the home screen. On ColorOS the system shades them to the wallpaper; the launcher
     * only ever turns its own light-bar flag off again (WallpaperResolver.resolveNavigationBarBrightness never sets it),
     * so elsewhere they stayed white on any wallpaper and vanished on a light one. This replaces that method: the bottom
     * of the wallpaper (the strip the launcher measures) decides, as for the status bar.
     *
     * The launcher's SystemUiController then recomputes the bars' flags from its own states whenever one changes (an
     * app opening or closing, the drawer, recents), and two of those states also set the navigation bar: the window's
     * (light only with the theme's dark labels) and the home screen's (the status bar's brightness). Either turned the
     * buttons white again over a light wallpaper. Both now carry this decision (see uiState).
     */

    private static final int LIGHT_NAV = 1, DARK_NAV = 2, LIGHT_STATUS = 4, DARK_STATUS = 8;

    /** One SystemUiController's states, and whether its scrim state was last set for the home screen. */
    private static final class Bars {
        final int[] states;
        boolean homeScrim;

        Bars(int[] states) {
            this.states = states;
        }
    }

    private static final java.util.WeakHashMap<android.view.Window, Bars> BARS = new java.util.WeakHashMap<>();
    private static java.lang.ref.WeakReference<android.view.Window> navWindow;
    private static int navBits; // LIGHT_NAV or DARK_NAV once the wallpaper was measured
    private static boolean homeNext;

    /** Called just before the launcher asks for the status bar's brightness to set the home screen's bars. */
    public static void homeBars() {
        homeNext = true;
    }

    /** SystemUiController.updateUiState(state, flags): its body, with the navigation bar's buttons decided here. */
    public static void uiState(android.view.Window window, int[] states, int state, int flags) {
        boolean home = homeNext;
        homeNext = false;
        if (window == null || states == null || state < 0 || state >= states.length) return;
        Bars bars = BARS.get(window);
        if (bars == null || bars.states != states) {
            bars = new Bars(states);
            BARS.put(window, bars);
        }
        boolean homeScrim = state == 1 ? home : bars.homeScrim;
        boolean changed = states[state] != flags;
        if (!changed && homeScrim == bars.homeScrim) return;
        states[state] = flags;
        bars.homeScrim = homeScrim;
        apply(window, bars, changed);
    }

    /**
     * The bars' flags from the states in order (later ones win), the window and home screen states with ours. The
     * status bar's only where the launcher recomputes them (the wallpaper's measure sets it directly, as ColorOS does).
     */
    private static void apply(android.view.Window window, Bars bars, boolean status) {
        android.view.View decor = window.getDecorView();
        boolean ours = navBits != 0 && navWindow != null && navWindow.get() == window;
        int old = decor.getSystemUiVisibility(), f = old;
        for (int i = 0; i < bars.states.length; i++) {
            int s = bars.states[i];
            if (ours && (i == 0 || (i == 1 && bars.homeScrim))) s = (s & ~(LIGHT_NAV | DARK_NAV)) | navBits;
            if ((s & LIGHT_NAV) != 0) f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else if ((s & DARK_NAV) != 0) f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (!status) continue;
            if ((s & LIGHT_STATUS) != 0) f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else if ((s & DARK_STATUS) != 0) f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (f != old) decor.setSystemUiVisibility(f);
    }

    /**
     * ToolbarUtils.setSystemUiVisibility(view, flags), the launcher's own pages (its settings, guides): ColorOS shades
     * the navigation bar's buttons to the bar's colour, so these pages paint the bar light grey and leave the buttons
     * white, invisible elsewhere. Over an opaque bar the buttons now follow its colour; over a see-through one (the home
     * screen) the flags stay as they are.
     */
    public static int barFlags(android.view.View view, int flags) {
        android.app.Activity activity = activity(view);
        if (activity == null) return flags;
        android.view.Window window = activity.getWindow();
        if (window == null || window.getDecorView() != view) return flags;
        int color = window.getNavigationBarColor();
        if (Color.alpha(color) < 0xc0) return flags;
        return luminance(color) >= 150 ? flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    }

    /** The activity a view (a window's decor view too: its own context is the app's) belongs to. */
    private static android.app.Activity activity(android.view.View view) {
        if (view == null) return null;
        android.content.Context c = view.getContext();
        if (view instanceof android.view.ViewGroup && ((android.view.ViewGroup) view).getChildCount() > 0) {
            c = ((android.view.ViewGroup) view).getChildAt(0).getContext();
        }
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) {
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return c instanceof android.app.Activity ? (android.app.Activity) c : null;
    }

    /** WallpaperResolver.resolveNavigationBarBrightness(launcher, wallpaper). */
    public static void navigationBar(Object resolver, final android.app.Activity launcher, Bitmap wallpaper) {
        if (resolver == null || launcher == null || wallpaper == null) return;
        int navHeight = 0;
        try {
            java.lang.reflect.Field f = resolver.getClass().getDeclaredField("mNavBarHeight");
            f.setAccessible(true);
            navHeight = ((Number) f.get(resolver)).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The bottom eighth of the image below.
        }
        Bitmap b = wallpaper;
        if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
        if (b == null || b.isRecycled()) return;
        int w = b.getWidth(), h = b.getHeight();
        int strip = navHeight > 0 ? Math.max(1, navHeight / 4) : Math.max(1, h / 8);
        int top = Math.max(0, h - strip);
        final boolean bright = brightness(b, top) >= 196;
        try {
            java.lang.reflect.Field f = resolver.getClass().getDeclaredField("mIsNavigationBarBright");
            f.setAccessible(true);
            f.setBoolean(resolver, bright);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The flag below is what shows.
        }
        launcher.runOnUiThread(() -> {
            android.view.Window window = launcher.getWindow();
            navWindow = new java.lang.ref.WeakReference<>(window);
            navBits = bright ? LIGHT_NAV : DARK_NAV;
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "navigation bar over the wallpaper: " + (bright ? "light" : "dark"));
            Bars bars = BARS.get(window);
            if (bars != null) {
                apply(window, bars, false);
                return;
            }
            android.view.View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int wanted = bright ? flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (wanted != flags) decor.setSystemUiVisibility(wanted);
        });
    }

    /** Average luminance (0-255) of rows top.. of a bitmap, from up to about 4000 samples. */
    private static int brightness(Bitmap b, int top) {
        int w = b.getWidth(), h = b.getHeight();
        int rows = Math.max(1, h - top);
        int step = Math.max(1, (int) Math.sqrt((double) w * rows / 4000));
        long sum = 0, n = 0;
        for (int y = top; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                sum += luminance(b.getPixel(x, y));
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    private static int luminance(int c) {
        return (int) (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c));
    }

    private static int blend(int c, int to, float f) {
        return Color.rgb((int) (Color.red(c) + (Color.red(to) - Color.red(c)) * f),
                (int) (Color.green(c) + (Color.green(to) - Color.green(c)) * f),
                (int) (Color.blue(c) + (Color.blue(to) - Color.blue(c)) * f));
    }
}
