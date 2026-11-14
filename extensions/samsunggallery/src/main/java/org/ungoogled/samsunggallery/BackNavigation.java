package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * Back off One UI from Android 15. Gallery's manifest turns on Android's back callbacks from Android 15 (as One UI 7
 * does), but Gallery reads One UI version 0 elsewhere and kept its pre-One UI 7 ways:
 * <ul>
 * <li>its own back (the viewer's back arrow, swipe down to close) typed a Back key into its window, which a window with
 * back callbacks drops -- they did nothing (the arrow's key also came while the viewer still ignored input);</li>
 * <li>the system's back skipped Gallery's activity, so the screen in front never handled it first: back in selection
 * mode closed Gallery instead of leaving the selection.</li>
 * </ul>
 * From Android 15 Gallery registers its activity's back callback as on One UI 7, and its own back goes to the back
 * dispatcher, after the touch that asked for it (closing the viewer from inside the swipe's touch handling crashed
 * Gallery). Before Android 15 a typed Back key becomes the activity's onBackPressed, which is what the key did.
 */
public final class BackNavigation {
    private static final String TAG = "UA";
    /** Gallery's typed-key "back with delay": key down now, key up 350 ms later. */
    private static final long DELAYED_BACK_MS = 350;

    private BackNavigation() {
    }

    /** Gallery's "back through callbacks" switch for its activity (One UI 7 and later), on from Android 15. */
    public static boolean usesBackCallbacks(boolean oneUi7) {
        return oneUi7 || Build.VERSION.SDK_INT >= 35;
    }

    /** Gallery is about to type key into its window; true when it was a Back key, handled here instead. */
    public static boolean simulateKey(Activity activity, int key) {
        return back(activity, key, 0);
    }

    /** As simulateKey, for Gallery's back that waits 350 ms. */
    public static boolean simulateKeyWithDelay(Activity activity, int key) {
        return back(activity, key, DELAYED_BACK_MS);
    }

    private static boolean back(Activity activity, int key, long delay) {
        if (key != KeyEvent.KEYCODE_BACK || activity == null) return false;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (usesBackCallbacks(false) && dispatch(activity)) return;
            activity.onBackPressed();
        }, delay);
        return true;
    }

    /** One UI 7's back: the activity's back dispatcher, where Gallery's own callback lets the screen in front go first. */
    private static boolean dispatch(Activity activity) {
        try {
            // Gallery switches its callback on and off as screens change; make sure it is current.
            try {
                activity.getClass().getMethod("updateBackPressedCallbackEnabled").invoke(activity);
            } catch (NoSuchMethodException notGallerys) {
                // Not Gallery's activity: the dispatcher alone.
            }
            Method get = activity.getClass().getMethod("getOnBackPressedDispatcher");
            Object dispatcher = get.invoke(activity);
            dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "back: " + e);
            return false;
        }
    }
}
