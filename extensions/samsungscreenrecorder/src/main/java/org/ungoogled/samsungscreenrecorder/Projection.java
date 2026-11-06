package org.ungoogled.samsungscreenrecorder;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen capture with the user's consent. One UI preinstalls Screen Recorder as the system (android.uid.system), which
 * records the screen with DisplayManager.createVirtualDisplay (CAPTURE_VIDEO_OUTPUT) and system sound with the remote
 * submix (CAPTURE_AUDIO_OUTPUT), asking nobody. A copy anyone can install is a regular app on every phone, One UI
 * included, so each recording asks through Android's own capture dialog (ConsentActivity), and that answer drives the
 * recording's virtual display and its app audio (Audio).
 */
public final class Projection {
    static final String TAG = "ScreenRecorderPort";
    static final String SERVICE = "com.sec.app.screenrecorder.encoder.RecordingThreadService";
    static final String LAUNCHER = "com.sec.app.screenrecorder.activity.LauncherActivity";
    private static final String ACTION_START = "com.sec.app.screenrecorder.ACTION_START";
    private static final String ACTION_STOP = "com.sec.app.screenrecorder.ACTION_STOP";
    private static final String PREFERENCES = "com.sec.app.screenrecorder_preferences";

    private static Context app;
    private static int resultCode;
    private static Intent consent;
    private static MediaProjection projection;

    private Projection() {
    }

    /** True where the app records as the system (Samsung's own preinstalled copy): Samsung's way then. */
    static boolean privileged(Context context) {
        return context.checkSelfPermission("android.permission.CAPTURE_VIDEO_OUTPUT") == PackageManager.PERMISSION_GRANTED;
    }

    /** The runtime permissions a recording needs that the user has not granted yet. */
    static String[] missing(Context context) {
        List<String> missing = new ArrayList<>();
        // The recordings go to DCIM/Video screenshots by path (the app targets Android 9, so it keeps that access).
        if (context.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.WRITE_EXTERNAL_STORAGE");
        }
        // App audio (AudioPlaybackCapture) needs the microphone permission too, not only the microphone itself.
        String sound = context.getSharedPreferences(PREFERENCES, 0).getString("record_sound", "mute");
        if (!"mute".equals(sound)
                && context.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.RECORD_AUDIO");
        }
        return missing.toArray(new String[0]);
    }

    /**
     * LauncherActivity, before it records: true when it should stop here because a permission is missing; then
     * ConsentActivity asks for it and opens LauncherActivity again.
     */
    public static boolean prepare(Activity activity) {
        if (privileged(activity)) return false;
        String[] missing = missing(activity);
        if (missing.length == 0) return false;
        Intent again = new Intent(activity.getIntent()).setClassName(activity, LAUNCHER);
        again.setFlags(0);
        activity.startActivity(new Intent(activity, ConsentActivity.class)
                .putExtra(ConsentActivity.EXTRA_PERMISSIONS, missing)
                .putExtra(ConsentActivity.EXTRA_THEN, again));
        return true;
    }

    /**
     * LauncherActivity's startService: the start of a recording first gets the user's capture consent (the dialog's
     * Start then starts the service); stopping goes ahead as before.
     */
    public static ComponentName startService(Context context, Intent service) {
        if (!ACTION_START.equals(service.getAction()) || privileged(context)) return context.startService(service);
        Intent ask = new Intent(context, ConsentActivity.class).putExtra(ConsentActivity.EXTRA_SERVICE, service);
        if (!(context instanceof Activity)) ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(ask);
        return null;
    }

    static synchronized Context context() {
        return app;
    }

    static void granted(Context context, int code, Intent data, Intent service) {
        synchronized (Projection.class) {
            app = context.getApplicationContext();
            resultCode = code;
            consent = data;
        }
        context.startService(service);
    }

    /** The recording's MediaProjection, made from the consent on first use (app audio or the screen). */
    static synchronized MediaProjection get() {
        if (projection != null || consent == null || app == null) return projection;
        MediaProjectionManager manager = app.getSystemService(MediaProjectionManager.class);
        MediaProjection made = manager.getMediaProjection(resultCode, consent);
        consent = null;
        if (made == null) return null;
        projection = made;
        made.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopped(made);
            }
        }, new Handler(Looper.getMainLooper()));
        return made;
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

    /** RecordingThreadService.onDestroy: the capture ends with the recording. */
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
     * The recording's virtual display: Samsung's call (DisplayManager, as the system) where the app is the system,
     * else the same display from the user's capture consent.
     */
    public static VirtualDisplay createVirtualDisplay(DisplayManager manager, String name, int width, int height,
                                                      int dpi, Surface surface, int flags) {
        MediaProjection current = get();
        if (current == null) return manager.createVirtualDisplay(name, width, height, dpi, surface, flags);
        return current.createVirtualDisplay(name, width, height, dpi, flags, surface, null, null);
    }
}
