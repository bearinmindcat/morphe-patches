package org.ungoogled.coloroslauncher;

import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Android's AnimationHandler (hidden) drives the launcher's spring animations (quickstep's DynamicAnimation and
 * friends register themselves as its frame callbacks). An installed app may not use it, and without frames those
 * animations never move: the app drawer, folders and state changes stayed where they started. Here the callbacks get
 * their frames from the thread's Choreographer instead, the way AnimationHandler gives them.
 */
final class Frames implements Choreographer.FrameCallback {
    private static final String TAG = "UA";
    private static final ThreadLocal<Frames> FRAMES = new ThreadLocal<>();
    private static final Map<Class<?>, Method> DO_FRAME = new HashMap<>();

    private final ArrayList<Object> callbacks = new ArrayList<>();
    private final Map<Object, Long> startAt = new HashMap<>();
    private boolean posted;

    private Frames() {
    }

    private static Frames frames() {
        Frames f = FRAMES.get();
        if (f == null) {
            f = new Frames();
            FRAMES.set(f);
        }
        return f;
    }

    /** AnimationHandler.addAnimationFrameCallback(callback, delay). */
    static void add(Object callback, long delay) {
        if (callback == null) return;
        Frames f = frames();
        if (!f.callbacks.contains(callback)) f.callbacks.add(callback);
        if (delay > 0) f.startAt.put(callback, SystemClock.uptimeMillis() + delay);
        f.post();
    }

    /** AnimationHandler.removeCallback(callback). */
    static void remove(Object callback) {
        Frames f = frames();
        int i = f.callbacks.indexOf(callback);
        if (i >= 0) f.callbacks.set(i, null); // removed after this frame, as AnimationHandler does
        f.startAt.remove(callback);
    }

    static int count() {
        int n = 0;
        for (Object c : frames().callbacks) if (c != null) n++;
        return n;
    }

    private void post() {
        if (posted) return;
        try {
            Choreographer.getInstance().postFrameCallback(this);
            posted = true;
        } catch (RuntimeException e) {
            // No looper on this thread: nothing can animate here.
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no frames on " + Thread.currentThread().getName());
        }
    }

    private static Method doFrame(Class<?> c) {
        synchronized (DO_FRAME) {
            if (DO_FRAME.containsKey(c)) return DO_FRAME.get(c);
            Method m = null;
            try {
                m = c.getMethod("doAnimationFrame", long.class);
                m.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "no doAnimationFrame in " + c.getName());
            }
            DO_FRAME.put(c, m);
            return m;
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        long now = SystemClock.uptimeMillis();
        long frameTime = frameTimeNanos / 1_000_000L;
        for (int i = 0; i < callbacks.size(); i++) {
            Object c = callbacks.get(i);
            if (c == null) continue;
            Long at = startAt.get(c);
            if (at != null) {
                if (at > now) continue;
                startAt.remove(c);
            }
            Method m = doFrame(c.getClass());
            if (m == null) continue;
            try {
                m.invoke(c, frameTime);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                callbacks.set(i, null);
            }
        }
        callbacks.removeIf(c -> c == null);
        if (!callbacks.isEmpty()) post();
    }
}
