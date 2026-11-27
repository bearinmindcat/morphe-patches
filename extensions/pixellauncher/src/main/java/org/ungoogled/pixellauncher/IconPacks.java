package org.ungoogled.pixellauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.Preference;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Icon pack" for Pixel Launcher: icons from an installed ADW / Nova style icon pack on the home screen, in the apps
 * list, folders, search results, the taskbar and recents. Every one of those reads app icons through the launcher's
 * icon provider into its icon cache, so the patch hooks there:
 *
 * <ul>
 *   <li>the icon provider ({@link #icon}): the pack's drawing first;</li>
 *   <li>the icon factory ({@link #options}): a pack's plain bitmap is drawn as it is, not shrunk onto a white
 *       adaptive-icon plate and clipped to the phone's icon shape like an old-style app icon;</li>
 *   <li>the theme manager's icon state ({@link #themeValue}, {@link #themeCode}): with a pack on, themed
 *       (monochrome) icons are off, and the pack is part of the theme code, which is what the icon cache is keyed on
 *       and what makes the launcher redraw every icon when it changes;</li>
 *   <li>the full-size icon for the drag preview and app animations ({@link #fullDrawable}): none for a pack's
 *       icon, so they show it as drawn.</li>
 * </ul>
 *
 * Pixel Launcher's own Home settings get an "Icon pack" row ({@link #addRow}). Nothing is added to the manifest, so
 * this works on a root (mount) install over the system launcher, which keeps the stock manifest.
 */
public final class IconPacks {
    static final String TAG = "PixelIconPack";

    private static final String PREFS = "ungoogled_icon_pack";
    private static final String KEY_PACK = "pack";
    private static final String KEY_SHAPE = "shape_others";
    static final String ROW_KEY = "ungoogled_icon_pack";

    /** The intents icon packs answer, for ADW, Nova, Apex, Action and Lawnchair. */
    private static final String[] PACK_ACTIONS = {
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
            "app.lawnchair.icons.THEMED_ICON",
    };

    private static final Object LOCK = new Object();
    private static Context app;
    private static boolean read;
    private static String packName = "";
    private static boolean shapeOthers = true;
    private static IconPack pack;
    private static boolean receiverOn;

    private static WeakReference<Object> themeManager = new WeakReference<>(null);
    private static final Map<Object, String> rootKeys = new WeakHashMap<>();

    private static Field wrapField;
    private static Field fullBleedField;
    private static boolean fieldsLooked;

    private IconPacks() {
    }

    /** The pack's drawing for the icon wraps it in this, so {@link #options} can tell it apart. */
    static final class PackIcon extends DrawableWrapper {
        PackIcon(Drawable drawable) {
            super(drawable);
        }
    }

    // ----- state -----

    private static void init(Context context) {
        synchronized (LOCK) {
            if (app == null && context != null) {
                Context a = context.getApplicationContext();
                app = a != null ? a : context;
            }
            if (read || app == null) return;
            read = true;
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            packName = prefs.getString(KEY_PACK, "");
            shapeOthers = prefs.getBoolean(KEY_SHAPE, true);
            pack = packName.isEmpty() ? null : IconPack.load(app, packName);
        }
        listenForPackChanges();
    }

    /** The pack in use, or null. */
    static IconPack current(Context context) {
        init(context);
        synchronized (LOCK) {
            return pack;
        }
    }

    static String selectedPackage(Context context) {
        init(context);
        synchronized (LOCK) {
            return packName;
        }
    }

    static boolean shapeOthers(Context context) {
        init(context);
        synchronized (LOCK) {
            return shapeOthers;
        }
    }

    /** Saves the choice, loads the pack (off the main thread) and makes the launcher redraw its icons. */
    static void choose(Context context, String pkg, boolean shape, Runnable done) {
        init(context);
        final String chosen = pkg == null ? "" : pkg;
        new Thread(() -> {
            IconPack loaded = chosen.isEmpty() ? null : IconPack.load(app, chosen);
            synchronized (LOCK) {
                packName = loaded == null ? "" : chosen;
                shapeOthers = shape;
                pack = loaded;
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_PACK, packName).putBoolean(KEY_SHAPE, shape).apply();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                refresh();
                if (done != null) done.run();
            });
        }, "IconPackLoader").start();
    }

    /** Re-reads the icon state: a changed theme code makes the launcher reload every icon. */
    static void refresh() {
        Object manager = themeManager.get();
        if (manager == null) {
            Log.w(TAG, "no theme manager yet");
            return;
        }
        try {
            verifyIconState(manager);
        } catch (Throwable t) {
            Log.w(TAG, "icon refresh failed", t);
        }
    }

    /** The pack app was updated or removed: read it again. */
    private static void listenForPackChanges() {
        Context context;
        synchronized (LOCK) {
            if (receiverOn || app == null) return;
            receiverOn = true;
            context = app;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    Uri data = intent.getData();
                    String pkg = data != null ? data.getSchemeSpecificPart() : null;
                    if (pkg == null) return;
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                            && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!pkg.equals(packName)) return;
                    }
                    new Thread(() -> {
                        IconPack loaded = IconPack.load(app, pkg);
                        synchronized (LOCK) {
                            if (!pkg.equals(packName)) return;
                            pack = loaded;
                        }
                        new Handler(Looper.getMainLooper()).post(IconPacks::refresh);
                    }, "IconPackLoader").start();
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "can't watch the icon pack", t);
        }
    }

    /** Installed icon packs: package -> label, sorted by label. */
    static Map<String, CharSequence> installedPacks(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String action : PACK_ACTIONS) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), PackageManager.GET_META_DATA)) {
                    if (ri.activityInfo != null && !found.containsKey(ri.activityInfo.packageName)) {
                        found.put(ri.activityInfo.packageName, ri);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        List<Map.Entry<String, CharSequence>> entries = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            CharSequence label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(e.getKey(), 0));
            } catch (Throwable t) {
                label = e.getValue().loadLabel(pm);
            }
            entries.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), label));
        }
        entries.sort((a, b) -> a.getValue().toString().compareToIgnoreCase(b.getValue().toString()));
        Map<String, CharSequence> result = new LinkedHashMap<>();
        for (Map.Entry<String, CharSequence> e : entries) result.put(e.getKey(), e.getValue());
        return result;
    }

    /** The pack app's name, or null when it isn't installed. */
    static CharSequence label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return null;
        }
    }

    // ----- hooks -----

    /**
     * Start of the launcher's IconProvider.getIcon(PackageItemInfo, ApplicationInfo, int): the pack's icon for this
     * activity, or null for the launcher's own.
     */
    public static Drawable icon(Context context, PackageItemInfo info, ApplicationInfo appInfo, int dpi) {
        if (info == null) return null;
        IconPack p = current(context);
        if (p == null) return null;
        boolean shape;
        synchronized (LOCK) {
            shape = shapeOthers;
        }
        try {
            Drawable d = p.icon(app, info, appInfo, dpi, shape);
            if (d == null) return null;
            // An adaptive icon from the pack goes the usual way (the phone's icon shape); anything else is drawn as is.
            return d instanceof AdaptiveIconDrawable ? d : new PackIcon(d);
        } catch (Throwable t) {
            Log.w(TAG, "icon for " + info.packageName, t);
            return null;
        }
    }

    /**
     * Start of BaseIconFactory.createBadgedIconBitmap(Drawable, IconOptions): for a pack's icon, neither wrap it in an
     * adaptive icon (a white plate, shrunk) nor draw it full bleed (a square the shape is cut from at draw time).
     */
    public static void options(Drawable icon, Object options) {
        if (!(icon instanceof PackIcon) || options == null) return;
        try {
            if (!fieldsLooked) {
                Class<?> c = options.getClass();
                wrapField = c.getField("wrapNonAdaptiveIcon");
                fullBleedField = c.getField("drawFullBleed");
                fieldsLooked = true;
            }
            wrapField.setBoolean(options, false);
            fullBleedField.set(options, Boolean.FALSE);
        } catch (Throwable t) {
            Log.w(TAG, "icon options", t);
        }
    }

    /**
     * After each getFullDrawable call (the drag preview, the app open and close animation): no full-size icon for a
     * pack's icon, so the launcher uses the icon as drawn instead of a shrunk copy on a white adaptive-icon plate. The
     * callers already handle "no full-size icon" (shortcuts and widgets have none).
     */
    public static Object fullDrawable(Object result) {
        if (result == null) return null;
        try {
            Object icon = null;
            if (result instanceof android.util.Pair) {
                icon = ((android.util.Pair<?, ?>) result).first;
            } else {
                for (Field f : result.getClass().getDeclaredFields()) {
                    if (AdaptiveIconDrawable.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        icon = f.get(result);
                        break;
                    }
                }
            }
            if (icon instanceof AdaptiveIconDrawable && hasPackIcon(((AdaptiveIconDrawable) icon).getForeground())) {
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "full-size icon", t);
        }
        return result;
    }

    /** Whether [drawable] is, or wraps, a pack's icon (the launcher wraps it in an inset drawable). */
    private static boolean hasPackIcon(Drawable drawable) {
        for (int i = 0; drawable != null && i < 8; i++) {
            if (drawable instanceof PackIcon) return true;
            if (!(drawable instanceof DrawableWrapper)) return false;
            drawable = ((DrawableWrapper) drawable).getDrawable();
        }
        return false;
    }

    /** Start of ThemeManager.parseIconState: keeps the manager to re-read the icon state when the pack changes. */
    public static void themeManager(Object manager, Context context) {
        themeManager = new WeakReference<>(manager);
        init(context);
    }

    /** The themed-icons choice read by ThemeManager.parseIconState: none while a pack is on. */
    public static Object themeValue(Object value) {
        return current(null) != null ? null : value;
    }

    /** The theme code ThemeManager.parseIconState computes without a theme: the pack is part of it. */
    public static String themeCode(String code) {
        IconPack p = current(null);
        if (p == null) return code;
        boolean shape;
        synchronized (LOCK) {
            shape = shapeOthers;
        }
        return code + ";iconpack=" + p.packageName + "@" + p.version + (shape && p.shapes() ? ";shaped" : "");
    }

    /** Filled in by the patch: ((ThemeManager) manager).verifyIconState(). */
    static void verifyIconState(Object manager) {
    }

    /** Start of the Home settings' onCreatePreferences: which screen this fragment shows (null = the main one). */
    public static void preferences(Object fragment, Bundle state, String rootKey) {
        synchronized (rootKeys) {
            rootKeys.put(fragment, rootKey == null ? "" : rootKey);
        }
    }

    /** End of the Home settings' onCreatePreferences: the "Icon pack" row on the main screen, at the top. */
    public static void addRow(Object fragment) {
        try {
            String root;
            synchronized (rootKeys) {
                root = rootKeys.get(fragment);
            }
            if (root == null || !root.isEmpty()) return;
            Object screen = Prefs.screen(fragment);
            if (!(screen instanceof Preference) || Prefs.find(screen, ROW_KEY) != null) return;
            Context context = contextOf((Preference) screen);
            if (context == null) return;
            Prefs.add(screen, new IconPackPreference(context));
        } catch (Throwable t) {
            Log.w(TAG, "can't add the Icon pack row", t);
        }
    }

    /** A Preference's Context (a field or a getter, depending on the androidx build). */
    private static Context contextOf(Preference preference) {
        for (Class<?> c = Preference.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == Context.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(preference);
                        if (v != null) return (Context) v;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }
}
