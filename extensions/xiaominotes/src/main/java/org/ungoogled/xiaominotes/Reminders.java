package org.ungoogled.xiaominotes;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.provider.Settings;

/**
 * A due reminder opens Notes' alarm screen straight from the alarm's broadcast. HyperOS lets its preinstalled Notes
 * start activities from the background; Android blocks that for an ordinary app without saying so (startActivity
 * returns, nothing opens), so a reminder went unseen. Where Android would block it this throws instead, and Notes
 * falls back to its own full-screen notification for the reminder.
 */
public final class Reminders {
    private Reminders() {
    }

    public static void startActivity(Context context, Intent intent) {
        if (!canStartActivity(context)) {
            throw new IllegalStateException("Android blocks starting the reminder screen from the background");
        }
        context.startActivity(intent);
    }

    private static boolean canStartActivity(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true; // preinstalled, as on HyperOS
        if (Settings.canDrawOverlays(context)) return true; // "Display over other apps" lifts the restriction
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND; // Notes is on screen
    }
}
