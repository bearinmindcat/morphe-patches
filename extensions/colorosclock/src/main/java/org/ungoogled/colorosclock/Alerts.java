package org.ungoogled.colorosclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Log;

/**
 * ColorOS's Clock opens its alarm and timer screens straight from the background, which Android refuses a re-signed
 * copy (unless "Display over other apps" is allowed): the alarm rang behind a dark screen, the timer too. The alarm's
 * ringing notification now carries its screen as a full-screen intent, so Android opens it over the lock screen (or
 * shows a heads-up to tap while the phone is in use); the timer, which has no ringing notification of its own, gets
 * one while its sound plays.
 */
public final class Alerts {
    private static final String CHANNEL = "org.ungoogled.colorosclock.alert_screen";
    private static final String ALARM_SCREEN = "com.oplus.alarmclock.alert.AlarmAlertFullScreen";
    private static final int TIMER_NOTIFICATION = 0x0C1A7100;
    /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_USER_ACTION | FLAG_ACTIVITY_NEW_DOCUMENT, as Clock starts the screens. */
    private static final int SCREEN_FLAGS = 0x100C0000;
    private static final int PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK / _SYSTEM_EXEMPTED (API 29 / 34). */
    private static final int TYPE_MEDIA_PLAYBACK = 0x2;
    private static final int TYPE_SYSTEM_EXEMPTED = 0x400;
    private static final String TAG = "UngoogledClock";

    private Alerts() {
    }

    /** Whether Android keeps this copy from opening a screen from the background. */
    static boolean blocked(Context c) {
        return c != null && !Settings.canDrawOverlays(c);
    }

    /** A silent channel important enough for full-screen intents (Clock's own is not). */
    private static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, label(c, "clock_notification_label", "Alarm and timer screens"),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    private static String label(Context c, String name, String fallback) {
        try {
            int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
            if (id != 0) return c.getString(id);
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static int icon(Context c) {
        int id = c.getResources().getIdentifier("ic_launcher_clock", "drawable", c.getPackageName());
        return id != 0 ? id : c.getApplicationInfo().icon;
    }

    /** The alarm's ringing notification (AlarmNotificationUtils.getAlarmRingingNotification), before it is built. */
    public static void alarmRinging(Notification.Builder builder, Context c, Parcelable schedule) {
        try {
            if (!blocked(c)) return;
            Intent screen = new Intent().setClassName(c.getPackageName(), ALARM_SCREEN)
                    .putExtra("intent.extra.alarm", schedule).addFlags(SCREEN_FLAGS);
            PendingIntent intent = PendingIntent.getActivity(c, 0x0C1A, screen, PENDING_FLAGS);
            channel(c);
            builder.setChannelId(CHANNEL).setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(intent, true)
                    .setContentIntent(intent);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The timer's ringing service asks whether the phone is locked: if not, ColorOS shows a pop-up over the app in use
     * (a fluid card or a floating window, neither of which a re-signed copy can show). Unless pop-ups are allowed, it
     * opens the timer screen instead, which then comes up as a heads-up notification to tap.
     */
    public static boolean timerKeyguardLocked(android.app.KeyguardManager keyguard) {
        Context c = application();
        return keyguard.isKeyguardLocked() || blocked(c);
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** startActivity(timer screen) from the timer's ringing service. */
    public static void startTimerScreen(Context c, Intent screen) {
        startTimerScreen(c, screen, null);
    }

    public static void startTimerScreen(Context c, Intent screen, Bundle options) {
        if (options == null) c.startActivity(screen);
        else c.startActivity(screen, options);
        try {
            if (!blocked(c) || inFront()) return;
            PendingIntent intent = PendingIntent.getActivity(c, 0x0C1B, new Intent(screen), PENDING_FLAGS);
            channel(c);
            String name = screen.getStringExtra("timer_name");
            Notification notification = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(icon(c))
                    .setContentTitle(name == null || name.isEmpty() ? label(c, "timer_title", "Timer") : name)
                    .setContentText(label(c, "timer_finished", "Time's up"))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setFullScreenIntent(intent, true)
                    .setContentIntent(intent)
                    .build();
            c.getSystemService(NotificationManager.class).notify(TIMER_NOTIFICATION, notification);
        } catch (RuntimeException ignored) {
        }
    }

    /** Whether one of Clock's screens is in front (then it may open the timer screen itself). */
    private static boolean inFront() {
        android.app.ActivityManager.RunningAppProcessInfo info = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(info);
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /** The timer's sound stopped (TimerKlaxon.onDestroy): its notification goes too. */
    public static void timerStopped(Context c) {
        try {
            c.getSystemService(NotificationManager.class).cancel(TIMER_NOTIFICATION);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * AlarmService.startForeground(id, notification), whose service ran as mediaPlayback. Android 15+ refuses that
     * type while the app's start allowance comes from BOOT_COMPLETED (an alarm due just after the first unlock
     * following a reboot), and the alarm crashed instead of ringing. Alarm clocks run as systemExempted instead, which
     * Android allows apps that set exact alarms (Google's Clock does the same); mediaPlayback stays the fallback.
     */
    public static void alarmForeground(Service service, int id, Notification notification) {
        if (Build.VERSION.SDK_INT < 29) {
            service.startForeground(id, notification);
            return;
        }
        alarmForeground(service, id, notification, TYPE_MEDIA_PLAYBACK);
    }

    /** ServiceCompat.startForeground(service, id, notification, type) for the alarm service's placeholder notification. */
    public static void alarmForeground(Service service, int id, Notification notification, int type) {
        if (Build.VERSION.SDK_INT >= 34 && systemExempted(service)) {
            try {
                service.startForeground(id, notification, TYPE_SYSTEM_EXEMPTED);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "alarm service refused as systemExempted, using type " + type, e);
            }
        }
        if (Build.VERSION.SDK_INT >= 29) service.startForeground(id, notification, type);
        else service.startForeground(id, notification);
    }

    /** Whether the service declares systemExempted and the app holds what Android requires for it (exact alarms). */
    private static boolean systemExempted(Service s) {
        try {
            ServiceInfo info = s.getPackageManager().getServiceInfo(new ComponentName(s, s.getClass()), 0);
            if ((info.getForegroundServiceType() & TYPE_SYSTEM_EXEMPTED) == 0) return false;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
        int granted = PackageManager.PERMISSION_GRANTED;
        return s.checkSelfPermission("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED") == granted
            && (s.checkSelfPermission("android.permission.USE_EXACT_ALARM") == granted
                || s.checkSelfPermission("android.permission.SCHEDULE_EXACT_ALARM") == granted);
    }
}
