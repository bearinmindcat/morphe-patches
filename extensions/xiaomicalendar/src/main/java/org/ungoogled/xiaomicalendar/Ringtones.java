package org.ungoogled.xiaomicalendar;

import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.provider.Settings;

/**
 * Calendar's reminder "Sound" rows open HyperOS's own ringtone picker, which stores the choice as the phone's calendar
 * or alarm sound itself; Calendar only reads those back. Without that picker (and without HyperOS's calendar sound
 * type) the rows open Android's own place for each sound instead: Calendar's notification settings (reminders are
 * notifications, each kind its own channel) and the phone's sound settings for the alarm sound alarm reminders use.
 */
public final class Ringtones {
    private static final String MIUI_PICKER = "miui.intent.action.RINGTONE_PICKER";

    private Ringtones() {
    }

    public static boolean hasMiuiPicker() {
        try {
            Context context = Calendars.context();
            return !context.getPackageManager().queryIntentActivities(new Intent(MIUI_PICKER), 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Turns the picker intent Calendar built into Android's settings screen for that sound. */
    public static void toSettings(Intent intent) {
        int type = intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1);
        intent.replaceExtras((android.os.Bundle) null);
        if (type == RingtoneManager.TYPE_ALARM) {
            intent.setAction(Settings.ACTION_SOUND_SETTINGS);
        } else {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, Calendars.context().getPackageName());
        }
    }
}
