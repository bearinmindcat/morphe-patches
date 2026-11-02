package org.ungoogled.colorosclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;

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
}
