package org.ungoogled.samsungroutines;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * As SystemUI on One UI the app may change system settings and open apps from the background; elsewhere the user has to
 * allow "Modify system settings" and "Display over other apps" for that. When a routine needs one that is not allowed,
 * the app says so once per run: on its own screens by opening the setting, from the engine with a notification that
 * opens it.
 */
public final class Access {
    private static final String TAG = "UA";
    private static final String CHANNEL = "ua_routines_access";
    private static boolean askedWrite;
    private static boolean askedOverlay;

    private Access() {
    }

    /**
     * The main screen opened. One UI grants its own apps notifications; elsewhere the app has to ask, or the engine's
     * notices (see below) and the routines' own notifications never show.
     */
    public static void onMainScreen(final Activity activity) {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!activity.isFinishing()) activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0x5541);
        });
    }

    /** A routine's System setting write was refused. */
    static void needWriteSettings(Context c) {
        if (askedWrite || Settings.System.canWrite(c)) return;
        askedWrite = true;
        ask(c, Settings.ACTION_MANAGE_WRITE_SETTINGS, 1, "Allow Modify system settings",
                "Routines need it to change brightness and other settings.");
    }

    /** The engine opens an activity (a routine's "Open an app"), which Android blocks from the background without it. */
    static void needOverlay(Context c) {
        if (askedOverlay || Settings.canDrawOverlays(c)) return;
        askedOverlay = true;
        ask(c, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 2, "Allow Display over other apps",
                "Routines need it to open apps.");
    }

    private static void ask(Context c, String action, int id, String title, String text) {
        Intent intent = new Intent(action, Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (!EventDelegation.isEngine(c)) {
                // The app's own screens are open: go straight to the setting.
                c.startActivity(intent);
                return;
            }
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Permissions for routines", NotificationManager.IMPORTANCE_DEFAULT));
            nm.notify(CHANNEL, id, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_IMMUTABLE))
                    .build());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not ask for " + action, e);
        }
    }

    // Context.startActivity in the app comes here.

    public static void startActivity(Context c, Intent intent) {
        if (EventDelegation.isEngine(c)) needOverlay(c);
        c.startActivity(intent);
    }

    public static void startActivity(Context c, Intent intent, Bundle options) {
        if (EventDelegation.isEngine(c)) needOverlay(c);
        c.startActivity(intent, options);
    }
}
