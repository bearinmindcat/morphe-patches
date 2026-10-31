package org.ungoogled.samsungclock;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;

/**
 * Clock's sounds off One UI. The timer's default tone is one of Samsung's system sounds (Chime_Time.ogg, Time_Up.ogg),
 * which no other phone has, so a timer rang silently: it falls back to the phone's own alarm sound. One UI's
 * Ringtone.semIsUriValid() is answered by actually opening the sound.
 */
public final class Sounds {
    private static final String TIMER_RINGTONE = "com.sec.android.app.clockpackage.ringtonepicker.util.RingtonePlayer$TimerRingtone";

    private Sounds() {
    }

    /** Whether a sound can be played: a file that exists, or a content Uri that opens. */
    static boolean playable(Context c, Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) return false;
        String scheme = uri.getScheme();
        if (scheme == null || ContentResolver.SCHEME_FILE.equals(scheme)) {
            String path = uri.getPath();
            return path != null && new File(path).canRead();
        }
        if (c == null) return true;
        try (AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ringtone.semIsUriValid(): the ringtone's sound can be opened. */
    static boolean valid(Ringtone ringtone) {
        try {
            Uri uri = (Uri) Ringtone.class.getMethod("getUri").invoke(ringtone);
            return playable(AlertScreen.application(), uri);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** Takes RingtonePlayer.TimerRingtone.getDefaultRingtoneUri: Samsung's default timer tone, or the phone's alarm sound. */
    public static Uri timerDefault(Context c) {
        Uri uri = null;
        try {
            uri = (Uri) Class.forName(TIMER_RINGTONE).getMethod("getDefaultRingtoneUri", Context.class).invoke(null, c);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Answered below.
        }
        if (playable(c, uri)) return uri;
        Uri alarm = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM);
        return playable(c, alarm) ? alarm : Settings.System.DEFAULT_ALARM_ALERT_URI;
    }
}
