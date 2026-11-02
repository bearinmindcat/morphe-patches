package org.ungoogled.colorosscreenrecorder;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Screen capture with the user's consent. On ColorOS the recorder is a system app and makes its own capture token
 * through ColorOS's AppPlatform (Epona "createProjection", MANAGE_MEDIA_PROJECTION); anywhere else that is refused,
 * and Android 14+ will not even let the recording service go foreground for screen capture without the user's
 * consent. So before a recording starts the app asks the user through Android's own capture dialog (ConsentActivity),
 * keeps the answer for that one recording, and the dialog's "Start" starts the recording.
 */
public final class Projection {
    static final String RECORDER_SERVICE = "com.oplus.screenrecorder.RecorderService";

    private static Intent consent;

    private Projection() {
    }

    /** True where the app may create capture tokens itself (preinstalled on ColorOS): ColorOS's own way then. */
    static boolean privileged(Context context) {
        return context.checkSelfPermission("android.permission.MANAGE_MEDIA_PROJECTION")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * MainActivity, right before it starts the recording service: opens the capture dialog for this recording and
     * answers true (the activity stops there; the dialog's answer starts the recording service with the same extras).
     */
    public static synchronized boolean ask(Context context, Intent request) {
        if (privileged(context)) return false;
        consent = null;
        Intent dialog = new Intent(context, ConsentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Bundle extras = request != null ? request.getExtras() : null;
        if (extras != null) dialog.putExtra(ConsentActivity.EXTRA_REQUEST, extras);
        try {
            context.startActivity(dialog);
        } catch (RuntimeException e) {
            android.util.Log.w("ScreenRecorderPort", "capture dialog not opened", e);
        }
        return true;
    }

    /**
     * The capture token in place of ColorOS's privileged one (the recording service asks for it twice per recording:
     * once when it starts, once to open the capture). Null where there is no consent.
     */
    public static synchronized Intent token() {
        return consent;
    }

    /** RecorderService.onDestroy: a consent is good for one recording only. */
    public static synchronized void clear() {
        consent = null;
    }

    static synchronized void granted(Context context, Intent data, Bundle request) {
        consent = data;
        Intent service = new Intent().setClassName(context, RECORDER_SERVICE);
        if (request != null) service.putExtras(request);
        context.startForegroundService(service);
    }
}
