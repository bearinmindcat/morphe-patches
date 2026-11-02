package org.ungoogled.colorosclock;

import android.content.Intent;
import android.media.RingtoneManager;
import android.provider.Settings;

/**
 * Clock picks alarm and timer sounds with ColorOS Settings' own sound picker (com.android.settings, action
 * oppo.intent.action.ACTION_MUSIC_SELECT or a RINGTONE_PICKER aimed at Settings), which no other phone has: tapping
 * Ringtone did nothing. Where that picker is missing it gets Android's sound picker, which answers the same way
 * (EXTRA_RINGTONE_PICKED_URI). On ColorOS (the framework's OplusBuild answers its version) the intent stays as it is.
 */
public final class Ringtones {
    private Ringtones() {
    }

    /** The picker intent Clock built (AlarmRingUtils), as it is returned. */
    public static Intent picker(Intent intent) {
        try {
            if (intent == null || com.oplus.os.OplusBuild.getOplusOSVERSION() > 0) return intent;
            Intent picker = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            if (intent.getExtras() != null) picker.putExtras(intent.getExtras());
            picker.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
            picker.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            picker.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI);
            return picker;
        } catch (RuntimeException | LinkageError e) {
            return intent;
        }
    }
}
