package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** The session Maps was last handed for each app: its browser's, the one followed, or the idle stand-in. */
    private static final Map<String, MediaSession.Token> CHOSEN = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Navigation's media panel while it is connected, and the app it shows. */
    private static WeakReference<Object> panel = new WeakReference<>(null);
    private static String panelPackage;
    private static long restarted;
    private static MediaSession idle;
    private static MediaSessionManager.OnActiveSessionsChangedListener watching;
    private static final Map<MediaSession.Token, MediaController.Callback> WATCHED = new HashMap<>();

    private PlayingMedia() {}

    /** Rewritten by the patch: the panel's connect and disconnect methods. */
    static String connectMethod() { return ""; }
    static String disconnectMethod() { return ""; }

    /** Whether Maps may read the active sessions: its listener has notification access. */
    public static boolean allowed(Context c) {
        try {
            return c.getSystemService(NotificationManager.class)
                    .isNotificationListenerAccessGranted(new ComponentName(c, MediaListener.class));
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String KEY_ASKED = "media_access_asked";
    private static boolean asking;

    /**
     * Navigation's media panel opening (with its button's Context) or the Default media app list (with its dialog):
     * once, while notification access is missing, ask for it. Android has no runtime prompt for it, only its own
     * settings page; "Not now" never asks again, Customization's row still leads there. A root mount install has no
     * listener to give it to, so it is never asked there.
     */
    public static void askAccess(Object from) {
        try {
            Dialog over = from instanceof Dialog ? (Dialog) from : null;
            Activity a = activityOf(over != null ? over.getContext() : (Context) from);
            if (a == null || asking || allowed(a) || !Screens.declared(a, MediaListener.class)) return;
            SharedPreferences prefs = a.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_ASKED, false)) return;
            asking = true;
            MAIN.postDelayed(() -> ask(a, over, prefs, 0), 300);
        } catch (Throwable t) {
            asking = false;
            android.util.Log.w("UA", "media access", t);
        }
    }

    /** Over Maps' own dialog, once that shows (it is shown right after it is made). */
    private static void ask(Activity a, Dialog over, SharedPreferences prefs, int tries) {
        try {
            if (a.isFinishing() || a.isDestroyed() || (over != null && !over.isShowing() && tries >= 10)) {
                asking = false;
                return;
            }
            if (over != null && !over.isShowing()) {
                MAIN.postDelayed(() -> ask(a, over, prefs, tries + 1), 200);
                return;
            }
            AlertDialog d = new AlertDialog.Builder(a)
                    .setMessage("Allow notification access so the media panel can show what any app is playing")
                    .setPositiveButton("Allow", (x, w) -> {
                        prefs.edit().putBoolean(KEY_ASKED, true).apply();
                        openAccess(a);
                    })
                    .setNegativeButton("Not now", (x, w) -> prefs.edit().putBoolean(KEY_ASKED, true).apply())
                    .setOnCancelListener(x -> prefs.edit().putBoolean(KEY_ASKED, true).apply())
                    .setOnDismissListener(x -> asking = false)
                    .create();
            d.setCanceledOnTouchOutside(false);
            d.show();
            android.util.Log.i("UA", "media: asked for notification access");
        } catch (Throwable t) {
            asking = false;
            android.util.Log.w("UA", "media access", t);
        }
    }

    /** Android's notification access page for Maps' listener, else the page listing every app's. */
    static void openAccess(Context c) {
        ComponentName listener = new ComponentName(c, MediaListener.class);
        int flags = c instanceof Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK;
        try {
            c.startActivity(new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", listener.flattenToString())
                    .addFlags(flags));
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(flags));
            } catch (Throwable ignored) {
            }
        }
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Called when Maps has an app's session from its media browser: the app's playing session, when that is another. */
    public static Object follow(Object compat) {
        try {
            Context c = Shapes.appContext();
            Object own = frameworkToken(compat);
            if (c == null || !(own instanceof MediaSession.Token)) return compat;
            MediaController browsed = new MediaController(c, (MediaSession.Token) own);
            String pkg = browsed.getPackageName();
            CHOSEN.put(pkg, (MediaSession.Token) own);
            if (playing(browsed)) return compat;
            MediaController now = sessionOf(c, pkg, false);
            if (now == null || now.getSessionToken().equals(own)) return compat;
            android.util.Log.i("UA", "media: following " + pkg + "'s playing session");
            CHOSEN.put(pkg, now.getSessionToken());
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
            // Nothing open yet (issue #78: Pocket Casts and TIDAL were offered only while playing): an idle stand-in
            // of Maps' own, so the app is offered and the panel waits; it moves to the app's session once it plays.
            MediaSession.Token chosen = open != null ? open.getSessionToken() : idle(c);
            if (chosen == null) return false;
            Field token = fieldOf(impl.getClass(), Class.forName(COMPAT_TOKEN, false, impl.getClass().getClassLoader()));
            token.set(impl, compatToken(token.getType(), chosen));
            CHOSEN.put(pkg, chosen);
            android.util.Log.i("UA", "media: " + pkg + " refused Maps, using " + (open != null ? "its open session" : "a stand-in until it plays"));
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UA", "media session", t);
            return false;
        }
    }

    /** Maps' own idle session, standing in for an app that refuses Maps and has nothing open yet. */
    private static synchronized MediaSession.Token idle(Context c) {
        if (!allowed(c)) return null;
        if (idle == null) idle = new MediaSession(c, "UA idle");
        return idle.getSessionToken();
    }

    /**
     * Navigation's media panel connects to its app (called with the panel and its connection). While it stays
     * connected, the panel follows the app's sessions: when the one it controls is not playing and another of the
     * app's is (Jellyfin's player starting after navigation did, Pocket Casts starting from the stand-in), it
     * reconnects, which hands it the playing one.
     */
    public static void panel(Object panel, Object connection) {
        try {
            Context c = Shapes.appContext();
            String pkg = servicePackage(connection);
            if (c == null || pkg == null || !allowed(c)) return;
            PlayingMedia.panel = new WeakReference<>(panel);
            panelPackage = pkg;
            watch(c);
        } catch (Throwable t) {
            android.util.Log.w("UA", "media panel", t);
        }
    }

    /** The panel disconnected. */
    public static void panelGone(Object panel) {
        if (PlayingMedia.panel.get() == panel) PlayingMedia.panel = new WeakReference<>(null);
    }

    private static synchronized void watch(Context c) {
        MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
        if (watching == null) {
            watching = PlayingMedia::sessionsChanged;
            m.addOnActiveSessionsChangedListener(watching, new ComponentName(c, MediaListener.class), MAIN);
        }
        sessionsChanged(m.getActiveSessions(new ComponentName(c, MediaListener.class)));
    }

    /** Watches every open session's playback, so a session starting to play is seen. */
    private static synchronized void sessionsChanged(List<MediaController> sessions) {
        Map<MediaSession.Token, MediaController> now = new HashMap<>();
        if (sessions != null) for (MediaController s : sessions) now.put(s.getSessionToken(), s);
        WATCHED.keySet().removeIf(t -> !now.containsKey(t));
        for (Map.Entry<MediaSession.Token, MediaController> e : now.entrySet()) {
            if (WATCHED.containsKey(e.getKey())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    later();
                }
            };
            e.getValue().registerCallback(cb, MAIN);
            WATCHED.put(e.getKey(), cb);
        }
        later();
    }

    private static final Runnable CHECK = PlayingMedia::check;

    private static void later() {
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, 700);
    }

    /** Reconnects the panel when its app plays in a session other than the one the panel controls. */
    private static void check() {
        try {
            Object p = panel.get();
            String pkg = panelPackage;
            Context c = Shapes.appContext();
            if (p == null || pkg == null || c == null) return;
            MediaSession.Token current = CHOSEN.get(pkg);
            MediaController play = null;
            for (MediaController s : c.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(c, MediaListener.class))) {
                if (!pkg.equals(s.getPackageName()) || !playing(s)) continue;
                if (s.getSessionToken().equals(current)) return;
                if (play == null) play = s;
            }
            if (play == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - restarted < 3000) {
                MAIN.postDelayed(CHECK, 3000);
                return;
            }
            restarted = now;
            android.util.Log.i("UA", "media: " + pkg + " plays elsewhere, reconnecting the panel");
            invoke(p, disconnectMethod());
            invoke(p, connectMethod());
        } catch (Throwable t) {
            android.util.Log.w("UA", "media follow", t);
        }
    }

    private static void invoke(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(o);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(o.getClass().getName() + "." + name + "()");
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
