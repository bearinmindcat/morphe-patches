package org.ungoogled.honorcalendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Reminders are notifications. On MagicOS Calendar holds that permission as a system app; elsewhere Android asks an app
 * built for Android 11 like this one only when it creates its first notification channel while on screen, which
 * Calendar does in the background at start-up, and refuses its own request: it was never asked and no reminder
 * showed. Once the calendar permission is granted, the home screen offers Android's notification settings for it,
 * once.
 */
public final class Notifications {
    private static final String PREFS = "ungoogled_honor";
    private static final String ASKED = "notifications_asked";

    private Notifications() {
    }

    public static void ask(Activity activity) {
        try {
            if (activity.isFinishing()) return;
            if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = activity.getSystemService(NotificationManager.class);
            if (nm == null || nm.areNotificationsEnabled()) return;
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(ASKED, false)) return;
            prefs.edit().putBoolean(ASKED, true).apply();
            new AlertDialog.Builder(activity)
                    .setTitle("Reminders need notifications")
                    .setMessage("Calendar shows event reminders as notifications, which are off for it. Turn them on in Android's settings?")
                    .setPositiveButton("Settings", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                        } catch (RuntimeException e) {
                            android.util.Log.w("UA", "notification settings: " + e);
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (RuntimeException e) {
            android.util.Log.w("UA", "notification permission: " + e);
        }
    }
}
