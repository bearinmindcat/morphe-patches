package org.ungoogled.xiaomiscreenrecorder;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

/**
 * Screen capture with the user's consent. On HyperOS Screen Recorder is a system app and makes its own capture token
 * through the hidden IMediaProjectionManager.createProjection (MANAGE_MEDIA_PROJECTION); anywhere else that call is
 * refused and Android 14+ will not even let the recording service go foreground for screen capture. So before a
 * recording starts, the app asks the user once through Android's own capture dialog (ConsentActivity), keeps the
 * answer for that one recording, and the dialog's "Start" starts the recording.
 */
public final class Projection {
    static final String RECORDER_SERVICE = "com.miui.screenrecorder.service.RecorderService";

    private static Intent consent;

    private Projection() {
    }

    /** True where the app may create capture tokens itself (preinstalled on HyperOS): Xiaomi's own way then. */
    private static boolean privileged(Context context) {
        return context.checkSelfPermission("android.permission.MANAGE_MEDIA_PROJECTION")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * RecorderService, right before it goes foreground to record: without a consent for this recording, opens the
     * capture dialog and answers true (the service stops there; the dialog's answer starts the recording again).
     */
    public static synchronized boolean ask(Service service) {
        if (consent != null || privileged(service)) return false;
        try {
            service.startActivity(new Intent(service, ConsentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            android.util.Log.w("ScreenRecorderPort", "capture dialog not opened", e);
        }
        return true;
    }

    /** The recording's capture token in place of HyperOS's privileged one; used once. Null where there is none. */
    public static synchronized Intent take() {
        Intent intent = consent;
        consent = null;
        return intent;
    }

    static synchronized void granted(Context context, Intent data) {
        consent = data;
        // The start-immediately path of the recording service; the microphone question was settled before the dialog.
        context.startService(new Intent().setClassName(context, RECORDER_SERVICE)
                .putExtra("is_start_immediately", true)
                .putExtra("need_check_audio_permission", false));
    }

    /**
     * RecorderService going foreground to record. HyperOS's recorder runs as the system, which may use the microphone
     * from the background; any other app's service needs the microphone type for that, or Android silences the
     * microphone as soon as the recorder leaves the screen. So the service declares it too where the microphone may be
     * used, falling back to screen capture alone if Android refuses it.
     */
    public static void startForeground(Service service, int id, Notification notification) {
        if (privileged(service)) {
            service.startForeground(id, notification);
            return;
        }
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (service.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            service.startForeground(id, notification, types);
        } catch (SecurityException e) {
            if (types == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) throw e;
            android.util.Log.w("ScreenRecorderPort", "microphone in the background refused: " + e.getMessage());
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }
    }
}
