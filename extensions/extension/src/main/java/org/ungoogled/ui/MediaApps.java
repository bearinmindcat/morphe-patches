package org.ungoogled.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final int COLOR = 0x7f060eaf, TOUCH_COLOR = 0x7f0606a6;

    private MediaApps() {}

    /** Rewritten by the patch: Maps' media app entry class, and its list builder's add method. */
    static String entryClass() { return ""; }
    static String addMethod() { return ""; }

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
