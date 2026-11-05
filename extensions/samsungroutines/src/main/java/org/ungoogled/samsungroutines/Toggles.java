package org.ungoogled.samsungroutines;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.List;

/**
 * Wi-Fi and mobile data actions. As SystemUI on One UI the app switches them itself; any other app may not: Android
 * ignores WifiManager.setWifiEnabled from apps (Android 10+) and refuses TelephonyManager.setDataEnabled without
 * MODIFY_PHONE_STATE, so the routine reported success and nothing changed. Here the action asks the user instead:
 * Android's own Wi-Fi or Internet panel opens when the app may open a screen, otherwise a notification opens it.
 */
public final class Toggles {
    private static final String TAG = "UA";
    private static final String CHANNEL = "ua_routines_toggles";
    private static final int WIFI = 0x5554_0001;
    private static final int DATA = 0x5554_0002;

    private Toggles() {
    }

    // WifiManager.setWifiEnabled(boolean) in the app comes here.
    public static boolean setWifiEnabled(WifiManager wifi, boolean on) {
        try {
            if (wifi.isWifiEnabled() == on) return true;
            if (wifi.setWifiEnabled(on)) return true;
        } catch (SecurityException e) {
            // Refused like the call above.
        }
        Context c = EngineHost.app;
        if (c != null) {
            ask(c, Settings.Panel.ACTION_WIFI, WIFI, on ? "Turn on Wi-Fi" : "Turn off Wi-Fi",
                    "A routine wants Wi-Fi " + (on ? "on" : "off") + ". Android lets only you switch it: tap here.");
        }
        return false;
    }

    // TelephonyManager.setDataEnabled(boolean) in the app comes here.
    public static void setDataEnabled(TelephonyManager phone, boolean on) {
        try {
            if (phone.isDataEnabled() == on) return;
        } catch (SecurityException e) {
            // Unknown: ask anyway.
        }
        // Needs MODIFY_PHONE_STATE, which only system apps hold. TelephonyManager swallows the refusal (it only logs
        // it), so read the state back instead of waiting for an exception.
        boolean now = !on;
        try {
            phone.setDataEnabled(on);
            now = phone.isDataEnabled();
        } catch (SecurityException e) {
            // Refused.
        }
        if (now == on) return;
        Context c = EngineHost.app;
        if (c != null) {
            ask(c, Settings.Panel.ACTION_INTERNET_CONNECTIVITY, DATA, on ? "Turn on mobile data" : "Turn off mobile data",
                    "A routine wants mobile data " + (on ? "on" : "off") + ". Android lets only you switch it: tap here.");
        }
    }

    /** Opens the panel when the app may open a screen now (in use, or Display over other apps), otherwise notifies. */
    private static void ask(Context c, String panel, int id, String title, String text) {
        Intent intent = new Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            if (canOpenScreen(c)) {
                nm.cancel(CHANNEL, id);
                c.startActivity(intent);
                return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "routines: could not open " + panel, e);
        }
        try {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wi-Fi and mobile data", NotificationManager.IMPORTANCE_HIGH));
            nm.notify(CHANNEL, id, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build());
        } catch (RuntimeException e) {
            Log.w(TAG, "routines: could not ask for " + panel, e);
        }
    }

    private static boolean canOpenScreen(Context c) {
        PowerManager power = c.getSystemService(PowerManager.class);
        KeyguardManager keyguard = c.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return false;
        if (Settings.canDrawOverlays(c)) return true;
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = am != null ? am.getRunningAppProcesses() : null;
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return true;
        }
        return false;
    }
}
