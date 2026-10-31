package org.ungoogled.samsungmessages;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Messages hands its send / receive work to IntentServices. On One UI it starts them as a system app, which Android lets
 * start services at any time; elsewhere its fallback starts them as foreground services they never become, and Android
 * killed the app ("did not then call Service.startForeground"). The work service is now started plainly, and when the
 * app is in the background this short foreground service starts it instead (an app with a foreground service may).
 */
public final class Relay extends Service {
    private static final String TAG = "UA";
    private static final String CHANNEL = "ua_messages_relay";
    private static final String EXTRA = "org.ungoogled.samsungmessages.RELAY";

    /** In place of the fallback's Context.startForegroundService(intent). */
    public static ComponentName startService(Context context, Intent intent) {
        try {
            return context.startService(intent);
        } catch (IllegalStateException e) {
            // Not allowed from the background: relay through a short foreground service.
            Intent relay = new Intent(context, Relay.class).putExtra(EXTRA, intent);
            context.startForegroundService(relay);
            return intent.getComponent();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sending and receiving", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Messages")
                .build();
        startForeground(0x4d534752, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        Intent work = intent == null ? null : intent.getParcelableExtra(EXTRA, Intent.class);
        if (work != null) {
            try {
                startService(work);
            } catch (RuntimeException e) {
                Log.w(TAG, "relay could not start " + work, e);
            }
        }
        // The work service runs on; this one only had to start it.
        new Handler(Looper.getMainLooper()).postDelayed(() -> stopSelf(startId), 10_000);
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
