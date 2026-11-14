package org.ungoogled.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Any media app (issue #9): navigation's media controls (Settings > Navigation > Default media
 * app) offer only Maps' partners -- Spotify, YouTube Music, Pandora, once Play Music. Every app
 * that offers its playback the way Android Auto takes it -- a MediaBrowserService, and Android
 * Auto support in its manifest -- joins them: Poweramp and the like.
 */
public final class MediaApps {
    /** Maps' partners: Spotify has a connection of its own; the others Maps lists itself, in their colours. */
    private static final Set<String> PARTNERS = new HashSet<>(Arrays.asList(
            "com.spotify.music", "com.google.android.music", "com.google.android.apps.youtube.music", "com.pandora.android"));
    /**
     * The partners Maps lists itself only when its server says so (issue #78): added after Maps' own list when
     * installed and not already there. Spotify is not one: Maps reaches it through Spotify's own connection.
     */
    private static final List<String> LISTED_PARTNERS = Arrays.asList(
            "com.google.android.apps.youtube.music", "com.google.android.music", "com.pandora.android");
    /** What Android Auto looks for in an app's manifest before it shows the app. */
    private static final String ANDROID_AUTO = "com.google.android.gms.car.application";
    /** color/quantum_googblue500 and color/google_blue200: Maps' own blue, for an app with no colour here. */
    private static final int COLOR = Res.color_quantum_googblue500, TOUCH_COLOR = Res.color_google_blue200;

    private MediaApps() {}

    /** Rewritten by the patch: Maps' media app entry class, and its list builder's add method. */
    static String entryClass() { return ""; }
    static String addMethod() { return ""; }
    /** Rewritten by the patch: the media browser callback's onConnectionFailed. */
    static String failedMethod() { return ""; }
    /** Rewritten by the patch: the media panel's "which app" getter (its package). */
    static String panelAppMethod() { return ""; }

    // ---- the media panel's row of other apps (issue #78) ----

    /** The apps chosen for the media panel's row, in order, comma-separated; none chosen: all of them. */
    static final String KEY_ROW = "media_row_apps";

    /** The apps chosen for the media panel's row; empty: all of them. */
    public static List<String> chosen(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String v = c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).getString(KEY_ROW, "");
            for (String p : v.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        } catch (Throwable ignored) {}
        return out;
    }

    public static void setChosen(Context c, List<String> packages) {
        c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROW, String.join(",", packages)).commit();
    }

    /** The apps Maps can offer in navigation now, by name: Android Auto media apps, open sessions, Maps' partners. */
    public static List<String> candidates(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> open = PlayingMedia.sessionPackages(c);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
            if (r.serviceInfo == null) continue;
            String pkg = r.serviceInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            if (PARTNERS.contains(pkg) || androidAuto(pm, pkg) || open.contains(pkg)) out.add(pkg);
        }
        Collections.sort(out, (a, b) -> label(c, a).compareToIgnoreCase(label(c, b)));
        return out;
    }

    public static String label(Context c, String pkg) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * The media panel collected its apps: the panel's own app first ({@code all} before {@code answers} were added),
     * then the others in the order their media browsers answered -- a different order each time, and the row under
     * the controls shows only the first five or so. Now: the panel's own app, the apps playing (or paused) now, then
     * the apps chosen in Customization in their order, or every app by name when none are chosen.
     */
    public static void swapOrder(List<Object> all, List<?> answers) {
        try {
            Context c = Shapes.appContext();
            if (c == null || all == null) return;
            int lead = Math.max(0, all.size() - (answers == null ? 0 : answers.size()));
            List<Object> rest = new ArrayList<>(all.subList(lead, all.size()));
            if (rest.isEmpty()) return;
            List<String> playing = PlayingMedia.inUse(c);
            List<String> chosen = chosen(c);
            Map<Object, String> pkg = new java.util.IdentityHashMap<>();
            for (Object o : rest) pkg.put(o, packageOf(o));
            List<Object> keep = new ArrayList<>();
            for (Object o : rest) {
                String p = pkg.get(o);
                if (p == null || chosen.isEmpty() || chosen.contains(p) || playing.contains(p)) keep.add(o);
            }
            Collections.sort(keep, (a, b) -> {
                String pa = pkg.get(a), pb = pkg.get(b);
                int r = Integer.compare(rank(playing, pa), rank(playing, pb));
                if (r == 0) r = Integer.compare(rank(chosen, pa), rank(chosen, pb));
                if (r == 0 && pa != null && pb != null) r = label(c, pa).compareToIgnoreCase(label(c, pb));
                return r;
            });
            List<Object> head = new ArrayList<>(all.subList(0, lead));
            all.clear();
            all.addAll(head);
            all.addAll(keep);
            StringBuilder order = new StringBuilder();
            for (Object o : all) order.append(order.length() == 0 ? "" : ", ").append(pkg.containsKey(o) ? pkg.get(o) : packageOf(o));
            android.util.Log.i("UA", "media: panel apps " + order + (chosen.isEmpty() ? "" : " (chosen " + chosen.size() + ")"));
        } catch (Throwable t) {
            android.util.Log.w("UA", "media panel apps", t);
        }
    }

    private static int rank(List<String> order, String pkg) {
        int i = pkg == null ? -1 : order.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static String packageOf(Object panelApp) {
        try {
            for (Class<?> k = panelApp.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Method m = k.getDeclaredMethod(panelAppMethod());
                    if (m.getReturnType() != String.class) continue;
                    m.setAccessible(true);
                    return (String) m.invoke(panelApp);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** How long an app's media browser may leave Maps' connection unanswered before it counts as refused. */
    private static final long ANSWER_MS = 10000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Boolean> ANSWERED = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Maps made the callback of a connection to an app's media browser and connects it right away (issue #78). It
     * waits for the answer with no time limit -- the Default media app list loads until every app answered -- and a
     * browser can stay silent for good: a service that binds null (as media3's does when it has no session for a
     * legacy browser) never connects nor fails. Ten seconds without an answer count as a refusal.
     */
    public static void connecting(Object callback, Object connection) {
        MAIN.postDelayed(() -> unanswered(callback, connection), ANSWER_MS);
    }

    /** The browser connected, refused or went away. */
    public static void answered(Object callback) {
        ANSWERED.put(callback, Boolean.TRUE);
    }

    private static void unanswered(Object callback, Object connection) {
        try {
            if (ANSWERED.containsKey(callback)) return;
            // Still this callback's browser, still not connected: Maps neither disconnected nor started anew since.
            Object impl = PlayingMedia.holderOf(connection, MediaBrowser.class);
            if (impl == null || !PlayingMedia.values(callback).contains(impl)) return;
            Field field = PlayingMedia.fieldOf(impl.getClass(), MediaBrowser.class);
            MediaBrowser browser = field == null ? null : (MediaBrowser) field.get(impl);
            if (browser == null || browser.isConnected()) return;
            ANSWERED.put(callback, Boolean.TRUE);
            android.util.Log.i("UA", "media: " + PlayingMedia.servicePackage(connection) + " never answered Maps, taken as refused");
            browser.disconnect();
            Method failed = callback.getClass().getDeclaredMethod(failedMethod());
            failed.setAccessible(true);
            failed.invoke(callback);
        } catch (Throwable t) {
            android.util.Log.w("UA", "media connection", t);
        }
    }

    /** Right after Maps starts its list of media apps: the others go in first. */
    public static void addAll(Object builder) {
        try {
            Context c = Shapes.appContext();
            if (c == null || builder == null) return;
            PackageManager pm = c.getPackageManager();
            Constructor<?> entry = Class.forName(entryClass(), false, builder.getClass().getClassLoader())
                    .getConstructor(String.class, int.class, int.class);
            Method add = method(builder.getClass(), addMethod());
            Set<String> seen = new HashSet<>();
            // Issue #78: an app without Android Auto support joins while it has a media session open (Follow
            // playing media). Every app listed is connected to at each navigation start; one already playing is
            // already running, the rest would all be started for it.
            Set<String> open = PlayingMedia.sessionPackages(c);
            for (ResolveInfo r : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                if (r.serviceInfo == null) continue;
                String pkg = r.serviceInfo.packageName;
                if (PARTNERS.contains(pkg) || pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                if (!androidAuto(pm, pkg) && !open.contains(pkg)) continue;
                add.invoke(builder, entry.newInstance(pkg, COLOR, TOUCH_COLOR));
            }
        } catch (Throwable t) {
            android.util.Log.w("UA", "media apps", t);
        }
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, Object.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "(Object)");
    }

    private static boolean androidAuto(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            return ai.metaData != null && ai.metaData.containsKey(ANDROID_AUTO);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Right after Maps finishes its list: the partners it left out, when installed with a media browser service. */
    public static Object complete(Object list) {
        try {
            Context c = Shapes.appContext();
            if (c == null || !(list instanceof List)) return list;
            Class<?> entryType = Class.forName(entryClass(), false, list.getClass().getClassLoader());
            Field name = null;
            for (Field f : entryType.getDeclaredFields()) {
                if (f.getType() == String.class) { name = f; break; }
            }
            if (name == null) return list;
            name.setAccessible(true);
            Set<String> listed = new HashSet<>();
            for (Object entry : (List<?>) list) listed.add((String) name.get(entry));
            Set<String> browsers = new HashSet<>();
            for (ResolveInfo r : c.getPackageManager().queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                if (r.serviceInfo != null) browsers.add(r.serviceInfo.packageName);
            }
            Constructor<?> entry = entryType.getConstructor(String.class, int.class, int.class);
            List<Object> all = new ArrayList<>((List<?>) list);
            for (String pkg : LISTED_PARTNERS) {
                if (!listed.contains(pkg) && browsers.contains(pkg)) all.add(entry.newInstance(pkg, COLOR, TOUCH_COLOR));
            }
            if (all.size() == ((List<?>) list).size()) return list;
            return Class.forName("com.google.common.collect.ImmutableList", false, list.getClass().getClassLoader())
                    .getMethod("copyOf", Collection.class).invoke(null, all);
        } catch (Throwable t) {
            android.util.Log.w("UA", "media apps", t);
            return list;
        }
    }

    /**
     * Navigation's "open the media app" (issue #78): Maps starts ACTION_MAIN for the package without a category,
     * which only reaches apps with a MAIN activity in the default category -- its partners have one, most apps
     * (Jellyfin, Pocket Casts) only have their launcher activity, so it said "Failed to launch application". Start
     * the app's own launcher entry instead, with Maps' flags and referrer.
     */
    public static Intent launchable(Context c, Intent intent) {
        try {
            String pkg = intent.getPackage();
            if (c == null || pkg == null) return intent;
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return intent;
            launch.addFlags(intent.getFlags());
            if (intent.getExtras() != null) launch.putExtras(intent.getExtras());
            return launch;
        } catch (Throwable t) {
            return intent;
        }
    }
}
