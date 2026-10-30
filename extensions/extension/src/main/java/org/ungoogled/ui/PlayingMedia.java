package org.ungoogled.ui;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Media playing elsewhere (issue #78). Navigation's media controls take an app's session from its media browser,
 * which missed two kinds of app: those whose browser refuses Maps ("No root for client": Pocket Casts, TIDAL,
 * YouTube Music), left out of Default media app altogether, and those whose browser hands out a session other than
 * the one playing (Jellyfin's Android Auto library has its own player), which showed the library's suggestions
 * instead of the song. Android's list of active media sessions answers both; only an app with notification access
 * may read it ({@link MediaListener}), so without it everything stays as it was.
 */
public final class PlayingMedia {
    private static final String COMPAT_TOKEN = "android.support.v4.media.session.MediaSessionCompat$Token";

    private PlayingMedia() {}

    /** Whether Maps may read the active sessions: its listener has notification access. */
    public static boolean allowed(Context c) {
        try {
            return c.getSystemService(NotificationManager.class)
                    .isNotificationListenerAccessGranted(new ComponentName(c, MediaListener.class));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called when Maps has an app's session from its media browser: the app's playing session, when that is another. */
    public static Object follow(Object compat) {
        try {
            Context c = Shapes.appContext();
            Object own = frameworkToken(compat);
            if (c == null || !(own instanceof MediaSession.Token)) return compat;
            MediaController browsed = new MediaController(c, (MediaSession.Token) own);
            if (playing(browsed)) return compat;
            MediaController now = sessionOf(c, browsed.getPackageName(), false);
            if (now == null || now.getSessionToken().equals(own)) return compat;
            android.util.Log.i("UA", "media: following " + browsed.getPackageName() + "'s playing session");
            return compatToken(compat.getClass(), now.getSessionToken());
        } catch (Throwable t) {
            return compat;
        }
    }

    /**
     * Called when an app's media browser refuses Maps, with Maps' connection to it (which keeps the service it found
     * and the browser): true when the app's open session was put in as the browser's, so Maps goes on as though it
     * had connected. A browser that never connected does not name its service, hence the service from the connection.
     */
    public static boolean adopt(Object connection) {
        try {
            Context c = Shapes.appContext();
            Object impl = holderOf(connection, MediaBrowser.class);
            String pkg = servicePackage(connection);
            if (c == null || impl == null || pkg == null) return false;
            MediaController open = sessionOf(c, pkg, true);
            if (open == null) return false;
            Field token = fieldOf(impl.getClass(), Class.forName(COMPAT_TOKEN, false, impl.getClass().getClassLoader()));
            token.set(impl, compatToken(token.getType(), open.getSessionToken()));
            android.util.Log.i("UA", "media: " + pkg + " refused Maps, using its open session");
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UA", "media session", t);
            return false;
        }
    }

    /** False for a browser that never connected (a session put in by {@link #adopt}): it has nothing to browse. */
    public static boolean browsable(Object browser) {
        try {
            Object impl = holderOf(browser, MediaBrowser.class);
            if (impl == null) return true;
            return ((MediaBrowser) fieldOf(impl.getClass(), MediaBrowser.class).get(impl)).isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The packages with a media session open; none without notification access. */
    static java.util.Set<String> sessionPackages(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (MediaController s : c.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(c, MediaListener.class))) {
                out.add(s.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** The app's playing session, or with {@code any} the first it has open; null without notification access. */
    private static MediaController sessionOf(Context c, String pkg, boolean any) {
        List<MediaController> all;
        try {
            all = c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c, MediaListener.class));
        } catch (Throwable t) {
            return null;
        }
        MediaController first = null;
        for (MediaController s : all) {
            if (!pkg.equals(s.getPackageName())) continue;
            if (playing(s)) return s;
            if (first == null) first = s;
        }
        return any ? first : null;
    }

    private static boolean playing(MediaController s) {
        PlaybackState p = s.getPlaybackState();
        if (p == null) return false;
        switch (p.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    /** The compat token's framework token. */
    private static Object frameworkToken(Object compat) throws IllegalAccessException {
        if (compat == null) return null;
        for (Field f : compat.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v = f.get(compat);
            if (v instanceof MediaSession.Token) return v;
        }
        return null;
    }

    /** A compat token around a framework token, with no extra binder: Maps then talks to the framework session. */
    private static Object compatToken(Class<?> type, MediaSession.Token token) throws ReflectiveOperationException {
        for (Constructor<?> k : type.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            if (p.length == 2 && p[0] == Object.class && !p[1].isPrimitive()) {
                k.setAccessible(true);
                return k.newInstance(token, null);
            }
        }
        throw new NoSuchMethodException(type.getName() + "(Object, binder)");
    }

    /** The package of the service a connection was made for: the ResolveInfo it keeps. */
    private static String servicePackage(Object connection) throws IllegalAccessException {
        for (Object v : values(connection)) {
            if (v instanceof android.content.pm.ResolveInfo && ((android.content.pm.ResolveInfo) v).serviceInfo != null) {
                return ((android.content.pm.ResolveInfo) v).serviceInfo.packageName;
            }
        }
        return null;
    }

    /** The object, or one it holds (two deep), with a field of the given type. */
    private static Object holderOf(Object o, Class<?> type) throws IllegalAccessException {
        if (o == null) return null;
        if (fieldOf(o.getClass(), type) != null) return o;
        for (Object v : values(o)) {
            if (fieldOf(v.getClass(), type) != null) return v;
        }
        for (Object v : values(o)) {
            if (v.getClass().getName().startsWith("android.") || v.getClass().getName().startsWith("java.")) continue;
            for (Object w : values(v)) {
                if (fieldOf(w.getClass(), type) != null) return w;
            }
        }
        return null;
    }

    /** The non-null object fields of an object. */
    private static List<Object> values(Object o) throws IllegalAccessException {
        List<Object> out = new java.util.ArrayList<>();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    private static Field fieldOf(Class<?> c, Class<?> type) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }
}
