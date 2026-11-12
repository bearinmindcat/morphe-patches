package org.ungoogled.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.TimeUnit;

/** User-visible, bounded data-sync work; an executor alone cannot keep an import alive. */
public final class EnrichmentService extends Service {
    private static final String CHANNEL = "ungoogled_import";
    private static final int NOTIFICATION = 0x5549;
    private static final String PAUSE = "org.ungoogled.library.PAUSE";
    private static final String CANCEL = "org.ungoogled.library.CANCEL";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static EnrichmentService instance;
    private static volatile boolean running;
    private static boolean starting;

    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (instance != EnrichmentService.this) return;
            if (PlaceEnrichment.shouldStopService()) {
                stopSelf();
                return;
            }
            boolean busy = PlaceEnrichment.isDoingWork();
            if (wakeLock != null) {
                if (busy && !wakeLock.isHeld()) wakeLock.acquire(TimeUnit.MINUTES.toMillis(5));
                else if (!busy && wakeLock.isHeld()) wakeLock.release();
            }
            if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(PlaceEnrichment.progress()));
            }
            MAIN.postDelayed(this, 1000);
        }
    };

    static boolean running() { return running; }

    static void request(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (running) { PlaceEnrichment.start(app); return; }
            if (starting || !PlaceEnrichment.isForeground()) return;
            starting = true;
            try {
                if (app.startForegroundService(new Intent(app, EnrichmentService.class)) == null)
                    throw new IllegalStateException("The installed manifest does not declare the import service");
            } catch (IllegalStateException | SecurityException e) {
                starting = false;
                PlaceEnrichment.serviceUnavailable(app,
                        "Android could not start background import. Reopen Local saved and tap Resume; a mount install may need a normal patched install.", e);
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Saved-place import", NotificationManager.IMPORTANCE_LOW));
        try {
            // Do not wait for the import/store lock before meeting Android's foreground deadline.
            startForeground(NOTIFICATION, notification("Preparing saved-place import."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            running = true;
            starting = false;
            NativePlaces.retain();
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ungoogled:Import");
                wakeLock.setReferenceCounted(false);
            }
            connectivity = getSystemService(ConnectivityManager.class);
            if (connectivity != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { PlaceEnrichment.networkChanged(EnrichmentService.this, false); }
                    @Override public void onLost(Network network) { PlaceEnrichment.networkChanged(EnrichmentService.this, true); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        boolean blocked = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
                        PlaceEnrichment.networkChanged(EnrichmentService.this, blocked);
                    }
                };
                connectivity.registerDefaultNetworkCallback(networkCallback);
            }
            MAIN.post(update);
        } catch (IllegalStateException | SecurityException e) {
            running = false;
            starting = false;
            PlaceEnrichment.serviceUnavailable(this, "Android could not start the visible import service. Tap Resume from Local saved.", e);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && PAUSE.equals(intent.getAction())) PlaceEnrichment.pauseAsync(this, "Paused by you. Tap Resume to continue.");
        else if (intent != null && CANCEL.equals(intent.getAction())) PlaceEnrichment.cancelAsync(this);
        else if (running) PlaceEnrichment.start(this);
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, Screens.intent(this, YouActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, EnrichmentService.class).setAction(PAUSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 2, new Intent(this, EnrichmentService.class).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Loading saved places").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Pause", pause).build())
                .addAction(new Notification.Action.Builder(null, "Cancel queue", cancel).build()).build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        PlaceEnrichment.pauseAsync(this, "Android's background data-sync time limit was reached. Reopen Local saved and tap Resume.");
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        starting = false;
        instance = null;
        MAIN.removeCallbacks(update);
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (IllegalArgumentException e) { android.util.Log.w("UA", "Import network callback was already removed", e); }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        NativePlaces.release();
        PlaceEnrichment.serviceStopped();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
