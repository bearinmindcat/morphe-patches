package org.ungoogled.ui;

import android.app.Activity;
import android.os.Build;

import java.util.concurrent.Executor;

/**
 * Hide screenshot sharing (issue #229): Maps registers screen capture callbacks (Android 14+) that open its share
 * sheet the moment a screenshot is taken, which also breaks the system's scrolling screenshot. Each registration
 * comes here instead and is left out while hiding it, so Maps never hears of screenshots. Its own class: the
 * callback type is Android 14's.
 */
public final class ScreenCapture {
    private ScreenCapture() {}

    /** In place of activity.registerScreenCaptureCallback(executor, callback). */
    public static void register(Activity activity, Executor executor, Object callback) {
        if (Shapes.HIDE_SCREENSHOT_SHARE || Build.VERSION.SDK_INT < 34) return;
        activity.registerScreenCaptureCallback(executor, (Activity.ScreenCaptureCallback) callback);
    }
}
