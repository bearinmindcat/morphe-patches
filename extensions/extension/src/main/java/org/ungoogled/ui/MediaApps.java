package org.ungoogled.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
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
            for (ResolveInfo r : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                if (r.serviceInfo == null) continue;
                String pkg = r.serviceInfo.packageName;
                if (PARTNERS.contains(pkg) || pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                if (!androidAuto(pm, pkg)) continue;
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
}
