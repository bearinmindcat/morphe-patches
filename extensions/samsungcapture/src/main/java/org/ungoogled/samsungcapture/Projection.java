package org.ungoogled.samsungcapture;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Screen capture with the user's consent. One UI preinstalls Samsung capture as a privileged system app, which gets its
 * MediaProjection from MediaProjectionManager.semGetMediaProjection (MANAGE_MEDIA_PROJECTION) and records system sound
 * with a Samsung audio source, asking nobody. A copy anyone can install is a regular app on every phone, One UI
 * included, so each recording first asks through Android's own capture dialog (ConsentActivity), and that answer drives
 * the recording's virtual display and its app audio (Audio).
 */
public final class Projection {
    static final String TAG = "SamsungCapturePort";
    static final String SERVICE = "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderService";
    static final String ACTION_START = "com.samsung.android.app.screenrecorder.ACTION_START";
    static final String ACTION_STOP = "com.samsung.android.app.screenrecorder.ACTION_STOP";
    /** ScreenRecorderUtils.startRecorderService's extra: the start came from the quick panel. */
    static final String FROM_QUICK_PANEL = "from_quick_panel";
    /** On the recording service's start intent once the user has agreed to the capture. */
    static final String EXTRA_CONSENTED = "org.ungoogled.samsungcapture.CONSENTED";
    /** The consent itself, carried to the recorder's own process (":screenrecorder"). */
    private static final String EXTRA_RESULT_CODE = "org.ungoogled.samsungcapture.RESULT_CODE";
    private static final String EXTRA_RESULT_DATA = "org.ungoogled.samsungcapture.RESULT_DATA";

    private static Context app;
    private static int resultCode;
    private static Intent consent;
    private static MediaProjection projection;

    private Projection() {
    }

    static Intent consentIntent(Context context, boolean fromQuickPanel) {
        return new Intent(context, ConsentActivity.class)
                .setAction(ACTION_START)
                .putExtra(FROM_QUICK_PANEL, fromQuickPanel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    /**
     * ScreenRecorderUtils.startRecorderService (the Quick settings tile, the launcher shortcut): a recording starts with
     * the capture consent. From the tile the shade closes as the consent screen opens.
     */
    public static void startRecorderService(Context context, boolean fromQuickPanel) {
        Intent ask = consentIntent(context, fromQuickPanel);
        if (context instanceof TileService) {
            PendingIntent pending = PendingIntent.getActivity(context, 0, ask,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            try {
                ((TileService) context).startActivityAndCollapse(pending);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "tile could not open the consent screen", e);
            }
        }
        context.startActivity(ask);
    }

    /**
     * ScreenRecorderService.onStartCommand, first: a start that did not come through the consent screen goes there
     * first. True when the service should do nothing more for this start.
     */
    public static boolean intercept(Service service, Intent intent) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return false;
        if (intent.getBooleanExtra(EXTRA_CONSENTED, false)) {
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            if (data != null) {
                synchronized (Projection.class) {
                    app = service.getApplicationContext();
                    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                    consent = data;
                }
            }
            return false;
        }
        synchronized (Projection.class) {
            if (projection != null || consent != null) return false;
        }
        try {
            service.startActivity(consentIntent(service, intent.getBooleanExtra(FROM_QUICK_PANEL, false)));
        } catch (RuntimeException e) {
            Log.w(TAG, "consent screen not opened", e);
        }
        return true;
    }

    /** ConsentActivity: the user agreed; the recording service starts with the consent (from the visible consent screen). */
    static void granted(Context context, int code, Intent data, boolean fromQuickPanel) {
        context.startService(new Intent().setClassName(context, SERVICE)
                .setAction(ACTION_START)
                .putExtra(FROM_QUICK_PANEL, fromQuickPanel)
                .putExtra(EXTRA_CONSENTED, true)
                .putExtra(EXTRA_RESULT_CODE, code)
                .putExtra(EXTRA_RESULT_DATA, data));
    }

    /** The current recording's MediaProjection, made from the consent on first use (app audio or the screen). */
    static synchronized MediaProjection current() {
        if (projection != null || consent == null || app == null) return projection;
        MediaProjectionManager manager = app.getSystemService(MediaProjectionManager.class);
        MediaProjection made;
        try {
            made = manager.getMediaProjection(resultCode, consent);
        } catch (RuntimeException e) {
            Log.w(TAG, "capture consent not usable", e);
            made = null;
        }
        consent = null;
        if (made == null) return null;
        final MediaProjection which = made;
        projection = which;
        which.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopped(which);
            }
        }, new Handler(Looper.getMainLooper()));
        return which;
    }

    /** SepWrapper.MediaProjectionManager.semGetMediaProjection(manager[, display]): the consented projection. */
    public static MediaProjection get(MediaProjectionManager manager, int display) {
        return current();
    }

    public static MediaProjection get(MediaProjectionManager manager) {
        return current();
    }

    /** Android ended the capture (the status bar's stop, another capture): the recording ends and is saved. */
    private static void stopped(MediaProjection which) {
        Context context;
        synchronized (Projection.class) {
            if (projection != which) return;
            projection = null;
            context = app;
        }
        if (context == null) return;
        try {
            context.startService(new Intent().setClassName(context, SERVICE).setAction(ACTION_STOP));
        } catch (RuntimeException e) {
            Log.w(TAG, "recording not stopped", e);
        }
    }

    /** ScreenRecorderService.onDestroy: the capture ends with the recording service. */
    public static void release() {
        MediaProjection done;
        synchronized (Projection.class) {
            done = projection;
            projection = null;
            consent = null;
        }
        if (done != null) done.stop();
    }

    /**
     * The recorder's check before a recording, a screenshot that comes back empty where a secure window is showing:
     * only the system may take that screenshot. Android's capture blacks out secure windows by itself, so the check
     * passes (a 1 x 1 picture).
     */
    public static android.graphics.Bitmap secureLayerCheck(String tag, Context context, float scale, int windowType) {
        return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
    }

    /**
     * The recording service's startForeground: Android wants the types it runs as, and allows the camera (selfie view)
     * and microphone types only with their permissions granted.
     */
    public static void startForeground(Service service, int id, Notification notification) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (service.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (service.checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        try {
            service.startForeground(id, notification, types);
        } catch (RuntimeException e) {
            // The camera and microphone types also need the app in the foreground at that moment.
            Log.w(TAG, "foreground as " + types + " refused, retrying with screen capture only", e);
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }
    }
}
