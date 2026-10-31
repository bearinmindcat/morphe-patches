package org.ungoogled.samsungclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Samsung's own Clock opens its alarm and timer screens straight from the background (a system permission). A
 * re-signed copy may not, unless "Display over other apps" is allowed: there its ringing notification carries the
 * screen as a full-screen intent, so Android opens it over the lock screen, or shows a heads-up to tap while the phone
 * is in use. The timer's notification opened its screen through a service, which Android also refuses, so it opens
 * the screen itself.
 */
public final class AlertScreen {
    private static final String CHANNEL = "org.ungoogled.samsungclock.alert_screen";
    private static final String TIMER_SERVICE = "com.sec.android.app.clockpackage.timer.service.TimerService";
    private static final String TIMER_SCREEN = "com.sec.android.app.clockpackage.timer.ui.activity.TimerAlarmActivity";
    /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_USER_ACTION, as Clock starts the screen. */
    private static final int SCREEN_FLAGS = 0x10040000;

    private AlertScreen() {
    }

    static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether Android keeps this copy from opening a screen from the background. */
    static boolean blocked(Context c) {
        if (c == null) return true;
        return c.checkSelfPermission("android.permission.START_ACTIVITIES_FROM_BACKGROUND") != PackageManager.PERMISSION_GRANTED
                && !Settings.canDrawOverlays(c);
    }

    /** A silent channel important enough for full-screen intents (Clock's own is not). */
    private static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Alarm and timer screens", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    /** Takes Notification.Builder.setContentIntent in the alarm's and timer's ringing notifications. */
    public static Notification.Builder content(Notification.Builder builder, PendingIntent intent) {
        builder.setContentIntent(intent);
        Context c = application();
        if (intent != null && intent.isActivity() && c != null && blocked(c)) {
            channel(c);
            builder.setChannelId(CHANNEL);
            builder.setFullScreenIntent(intent, true);
        }
        return builder;
    }

    /** Takes PendingIntent.getService in the timer's ringing notification: its screen, opened directly. */
    public static PendingIntent service(Context c, int code, Intent intent, int flags) {
        ComponentName component = intent.getComponent();
        if (component != null && TIMER_SERVICE.equals(component.getClassName()) && blocked(c)) {
            Intent screen = new Intent(intent).setAction(null).setClassName(c.getPackageName(), TIMER_SCREEN).addFlags(SCREEN_FLAGS);
            return PendingIntent.getActivity(c, code, screen, flags);
        }
        return PendingIntent.getService(c, code, intent, flags);
    }
}
