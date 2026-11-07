package org.ungoogled.coloroscamera;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * "No background running" for OnePlus Camera's processing service (ApsService), which the camera itself calls its
 * daemon service. Stock, it is started every time the camera's process starts -- also when ColorOS preloads the
 * camera for a fast launch, with nobody using it -- returns START_STICKY so Android starts it again after closing it,
 * and is never stopped: once photos are saved it only leaves the foreground (on 6 GB phones not even that), so the
 * camera stays running in the background for good.
 *
 * Here the service keeps the same job and the same protection while the camera is open and while photos are still
 * processing after it closes, then stops at the point where the camera itself decides there is nothing left to do.
 */
public final class Background {
    private static final String TAG = "OnePlusCamera";

    /** The service's own "can it leave the foreground now?" check. It returns at once while the camera screen shows. */
    private static final String CHECK = "checkStopForegroundService";

    /** Long enough for the camera screen to come up when the user started the camera. */
    private static final long CHECK_DELAY_MS = 10_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The running service instance; a check scheduled for an instance destroyed since then is dropped. */
    private static volatile WeakReference<Service> current = new WeakReference<>(null);

    private Background() {
    }

    /**
     * Start of onStartCommand. Started without the camera screen (ColorOS preloading the camera), nothing would ever
     * stop it: run the service's own check a little later, which ends in {@link #idle} when there is nothing to do.
     */
    public static void started(Service service) {
        final WeakReference<Service> ref = new WeakReference<>(service);
        current = ref;
        MAIN.postDelayed(() -> {
            Service s = ref.get();
            if (s != null && s == current.get()) check(s);
        }, CHECK_DELAY_MS);
    }

    /** Start of onDestroy. */
    public static void destroyed(Service service) {
        if (current.get() == service) current = new WeakReference<>(null);
    }

    private static void check(Service service) {
        try {
            Method method = service.getClass().getDeclaredMethod(CHECK);
            method.setAccessible(true);
            method.invoke(service);
        } catch (Throwable t) {
            Log.w(TAG, "processing service check failed", t);
        }
    }

    /**
     * When the camera starts its preview (the service goes to the foreground) and when it closes (onStop): keep the
     * service started, as stock, so photos still processing after the camera closes finish. Without this a service
     * stopped earlier would only be bound, and closing the camera would end it mid-photo.
     */
    public static void keepStarted(Service service) {
        try {
            service.startService(new Intent(service, service.getClass()));
        } catch (Throwable t) {
            Log.w(TAG, "can't keep the processing service started", t);
        }
    }

    /**
     * The point where the camera has decided the service can leave the foreground: the camera screen is closed and
     * the photo queue is empty, or it gave up waiting for it. Stop the service then, unless something is still
     * processing. A camera screen still bound to it keeps it alive; it ends when the screen goes.
     */
    public static void idle(Service service, boolean busy) {
        if (busy) {
            Log.i(TAG, "processing service kept: photos still processing");
            return;
        }
        Log.i(TAG, "processing service stopped: camera closed, nothing processing");
        service.stopSelf();
    }
}
