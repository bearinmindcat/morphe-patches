package org.ungoogled.honorscreenrecorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Screen capture with the user's consent. On an Honor phone the recorder is a system app: it records the screen
 * through a SurfaceControl display (CAPTURE_VIDEO_OUTPUT) and makes its own capture token for device audio through
 * MediaProjectionManagerEx (MANAGE_MEDIA_PROJECTION). Anywhere else both are refused, and Android 10+ will not let the
 * recording service capture the screen without the user's consent. So before a recording starts the app asks the user
 * through Android's own capture dialog (ConsentActivity); that answer, kept for that one recording, gives the one
 * MediaProjection that records the screen (Capture) and the device audio.
 */
public final class Projection {
    static final String TAG = "HonorScreenRecorderPort";
    static final String SERVICE = "com.hihonor.screenrecorder.ScreenRecordService";
    static final String START = "com.hihonor.screenrecorder.Start";
    static final String STOP = "com.hihonor.screenrecorder.Stop";
    /** A consent nobody used within this long is asked for again (Android drops unused ones too). */
    private static final long CONSENT_MILLIS = 4 * 60 * 1000L;

    private static Context application;
    private static Intent consent;
    private static long consentAt;
    private static MediaProjection projection;
    private static boolean stopping;

    private Projection() {
    }

    static synchronized Context context() {
        return application;
    }

    private static boolean fresh() {
        return consent != null && SystemClock.elapsedRealtime() - consentAt < CONSENT_MILLIS;
    }

    /**
     * ScreenRecordService, where a new recording begins (after the recorder's own permission checks): without a
     * consent for it, opens Android's capture dialog and answers true (the service stops there; the dialog's "Start"
     * starts the recording again).
     */
    public static synchronized boolean ask(Service service) {
        application = service.getApplicationContext();
        if (fresh() || projection != null) return false;
        consent = null;
        Intent dialog = new Intent(service, ConsentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            service.startActivity(dialog);
        } catch (RuntimeException e) {
            Log.w(TAG, "capture dialog not opened", e);
        }
        return true;
    }

    /** The capture dialog's "Start": keeps the answer and starts the recording. */
    static synchronized void granted(Activity activity, Intent data) {
        application = activity.getApplicationContext();
        consent = data;
        consentAt = SystemClock.elapsedRealtime();
        start(activity);
    }

    /** Starts (or, while one runs, stops) a recording, as MagicOS's Screen recording tile does. */
    static void start(Context context) {
        Intent intent = new Intent(START).setClassName(context, SERVICE);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "recording not started", e);
        }
    }

    /** MediaProjectionManagerEx.createMediaProjection (MagicOS's privileged token): the user's consent instead. */
    public static synchronized Intent createMediaProjection() {
        if (fresh()) return consent;
        // The projection for this recording exists already (getMediaProjection answers it).
        return projection != null ? new Intent() : null;
    }

    /**
     * MediaProjectionManager.getMediaProjection: one projection per consent, shared by the screen (Capture) and the
     * device audio; Android lets a consent be used once.
     */
    public static synchronized MediaProjection getMediaProjection(MediaProjectionManager manager, int code, Intent data) {
        if (projection != null) return projection;
        if (manager == null || !fresh()) return null;
        MediaProjection created = manager.getMediaProjection(Activity.RESULT_OK, consent);
        consent = null;
        if (created == null) return null;
        stopping = false;
        created.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopped(created);
            }
        }, new Handler(Looper.getMainLooper()));
        projection = created;
        Tiles.update(application);
        return created;
    }

    /** The projection for Capture (when the device audio did not make it first). */
    static synchronized MediaProjection projection() {
        if (projection != null || application == null) return projection;
        return getMediaProjection(application.getSystemService(MediaProjectionManager.class), Activity.RESULT_OK, consent);
    }

    static synchronized boolean recording() {
        return projection != null;
    }

    /** The recorder's own MediaProjection.stop (its audio recorder, once the recording ends). */
    public static void stop(MediaProjection which) {
        synchronized (Projection.class) {
            if (which == projection) stopping = true;
        }
        if (which != null) which.stop();
    }

    /** ScreenRecordService.onDestroy: nothing of this recording outlives the service. */
    public static void release() {
        MediaProjection which;
        synchronized (Projection.class) {
            which = projection;
            stopping = true;
            consent = null;
        }
        if (which != null) {
            try {
                which.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "projection not stopped", e);
            }
        }
    }

    private static void stopped(MediaProjection which) {
        boolean ours;
        synchronized (Projection.class) {
            if (which != projection) return;
            projection = null;
            ours = stopping;
            stopping = false;
        }
        Capture.projectionStopped();
        Tiles.update(application);
        // Stopped by Android (its "Stop sharing" button, another app's capture): the recording ends and is saved.
        if (!ours && application != null) {
            try {
                application.startService(new Intent(STOP).setClassName(application, SERVICE));
            } catch (RuntimeException e) {
                Log.w(TAG, "recording not stopped", e);
            }
        }
    }

    /**
     * The recording notification is the recorder's own view ("Recording screen" and a stop button), which MagicOS's
     * SystemUI draws but Android's left empty (the header only). The same as a standard notification: that text and a
     * Stop action.
     */
    private static Notification standard(Service service, Notification custom) {
        if (custom == null || custom.contentView == null || !service.getPackageName().equals(custom.contentView.getPackage())) {
            return custom;
        }
        Resources resources = service.getResources();
        String pkg = service.getPackageName();
        int title = resources.getIdentifier("recording", "string", pkg);
        int stop = resources.getIdentifier("end_tv_text", "string", pkg);
        int icon = resources.getIdentifier("ic_screen_end", "drawable", pkg);
        if (title == 0 || stop == 0 || icon == 0 || Build.VERSION.SDK_INT < 26) return custom;
        // Through the recorder's start activity (a start while recording stops it, as MagicOS's tile does), so the
        // shade closes over the saved recording's thumbnail.
        PendingIntent end = PendingIntent.getActivity(service, 0, new Intent(Tiles.LMT).setClassName(pkg, Tiles.START_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(service, custom.getChannelId())
                .setSmallIcon(custom.getSmallIcon())
                .setOngoing(true)
                .setShowWhen(false)
                .setContentTitle(resources.getString(title))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(service, icon), resources.getString(stop), end).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    /**
     * The recording service's startForeground: with the types it records with (Android 14+ refuses a screen capture
     * service without its type, and silences the microphone of a background service that does not declare it).
     */
    public static void startForeground(Service service, int id, Notification notification) {
        notification = standard(service, notification);
        if (Build.VERSION.SDK_INT < 29) {
            service.startForeground(id, notification);
            return;
        }
        int types = 0;
        synchronized (Projection.class) {
            if (projection != null || fresh()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        if (Build.VERSION.SDK_INT >= 30 && service.checkSelfPermission("android.permission.RECORD_AUDIO")
                == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            service.startForeground(id, notification, types);
        } catch (RuntimeException e) {
            // e.g. the microphone refused to a service started from the background: record the screen without it
            if ((types & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) == 0) throw e;
            Log.w(TAG, "foreground without the microphone", e);
            service.startForeground(id, notification, types & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
    }

    /**
     * The recording notification's channel, created at importance "low": Android shows such a notification minimised
     * (its header only), so the recorder's own view with the stop button never showed. The recording channel is
     * made a silent default one instead, which shows that view.
     */
    public static void createNotificationChannel(NotificationManager manager, NotificationChannel channel) {
        if (Build.VERSION.SDK_INT >= 26 && "notification_channel_sound_screenrecorder_app".equals(channel.getId())
                && channel.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            channel.setImportance(NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
        }
        manager.createNotificationChannel(channel);
    }
}
