package org.ungoogled.samsungroutines;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * On One UI the routine engine lives in SystemUI's process, which always runs and may start services at any time.
 * Elsewhere Android freezes a background app and refuses its service starts, so conditions changed unseen and no
 * routine ever ran. This foreground service keeps the engine running (a silent notification while it does), started
 * whenever the app starts and after a reboot or update (see Boot).
 */
public final class EngineHost extends Service {
    private static final String TAG = "UA";
    private static final String CHANNEL = "ua_routines_engine";

    /** From the app's onCreate, in each of its processes. The engine process also brings back its event watches. */
    public static void start(Context context) {
        if (EventDelegation.isEngine(context)) EventDelegation.sync(context);
        try {
            context.startForegroundService(new Intent(context, EngineHost.class));
        } catch (RuntimeException e) {
            // Started from the background (no exemption): the next start, reboot or app launch brings it.
            Log.w(TAG, "routines engine host not started: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Routines engine", NotificationManager.IMPORTANCE_MIN));
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Modes and Routines is running")
                .setOngoing(true);
        if (open != null) n.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        startForeground(0x524f5554, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The screens' process changed the event watches (see EventDelegation).
        if (intent != null && EventDelegation.ACTION_SYNC.equals(intent.getAction())) EventDelegation.sync(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Brings the engine host back after a reboot or an update. */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            start(context);
        }
    }
}
