package org.ungoogled.samsungsoundassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.os.IBinder;

/**
 * Runs while any app is turned down: Android delivers the "audio session opened" announcements only to a running
 * app, so this keeps one listening (and restarts after a reboot, see AppVolumeBoot).
 */
public final class AppVolumeService extends Service {
    private static final String CHANNEL = "ua_app_volumes";
    private final BroadcastReceiver sessions = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            AppVolumes.onSession(c, intent);
        }
    };

    static void update(Context c) {
        Intent intent = new Intent(c, AppVolumeService.class);
        if (AppVolumes.anyDown(c)) {
            try {
                c.startForegroundService(intent);
            } catch (RuntimeException e) {
                android.util.Log.w("UA", "app volumes service", e);
            }
        } else {
            c.stopService(intent);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Individual app volumes", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Individual app volumes are on")
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        registerReceiver(sessions, filter, Context.RECEIVER_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppVolumes.anyDown(this)) stopSelf();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        unregisterReceiver(sessions);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
