package org.ungoogled.honorrecorder;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.content.Context;
import android.media.AudioManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Honor Recorder's phone-call checks (patches/.../honorrecorder sends its TelephonyManager getCallState / listen calls
 * here). On an Honor phone Recorder is a system app reading the call state with READ_PRIVILEGED_PHONE_STATE; any other
 * app needs READ_PHONE_STATE, which Recorder never asks for, so pressing record threw a SecurityException. The call
 * state then comes from the audio mode (in a call / ringing), and a call-state listener follows audio mode changes:
 * recording still pauses when a call comes in. Recording also silences the ringer (and restores it after), which
 * needs Do Not Disturb access ("Not allowed to change Do Not Disturb state" killed the recording service): without that
 * access the ringer is left as it is.
 */
public final class Phone {
    private static final Map<PhoneStateListener, AudioManager.OnModeChangedListener> FOLLOWERS = new WeakHashMap<>();

    private Phone() {
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static int fromMode(int mode) {
        switch (mode) {
            case AudioManager.MODE_RINGTONE:
                return TelephonyManager.CALL_STATE_RINGING;
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
            case AudioManager.MODE_CALL_SCREENING:
                return TelephonyManager.CALL_STATE_OFFHOOK;
            default:
                return TelephonyManager.CALL_STATE_IDLE;
        }
    }

    public static int getCallState(TelephonyManager telephony) {
        try {
            return telephony.getCallState();
        } catch (SecurityException e) {
            Context c = application();
            AudioManager audio = c == null ? null : c.getSystemService(AudioManager.class);
            return audio == null ? TelephonyManager.CALL_STATE_IDLE : fromMode(audio.getMode());
        }
    }

    public static void listen(TelephonyManager telephony, PhoneStateListener listener, int events) {
        try {
            telephony.listen(listener, events);
            return;
        } catch (SecurityException e) {
            // no READ_PHONE_STATE: follow the audio mode instead
        }
        Context c = application();
        AudioManager audio = c == null ? null : c.getSystemService(AudioManager.class);
        if (audio == null || listener == null) return;
        synchronized (FOLLOWERS) {
            AudioManager.OnModeChangedListener old = FOLLOWERS.remove(listener);
            if (old != null) audio.removeOnModeChangedListener(old);
            if ((events & PhoneStateListener.LISTEN_CALL_STATE) == 0) return;
            AudioManager.OnModeChangedListener follower = new AudioManager.OnModeChangedListener() {
                @Override
                public void onModeChanged(int mode) {
                    listener.onCallStateChanged(fromMode(mode), null);
                }
            };
            FOLLOWERS.put(listener, follower);
            audio.addOnModeChangedListener(c.getMainExecutor(), follower);
        }
    }

    /** The ringer mode RecordService reads before silencing it: "already silent" when it may not silence it. */
    public static int ringerModeBeforeMute(AudioManager audio) {
        Context c = application();
        NotificationManager notifications = c == null ? null : c.getSystemService(NotificationManager.class);
        if (notifications != null && !notifications.isNotificationPolicyAccessGranted()) return AudioManager.RINGER_MODE_SILENT;
        return audio.getRingerMode();
    }

    public static void setRingerMode(AudioManager audio, int mode) {
        try {
            audio.setRingerMode(mode);
        } catch (SecurityException e) {
            // no Do Not Disturb access: the ringer stays as it is
        }
    }

    /**
     * Asks once for the notification permission (Android 13+), which Recorder declares but, as a system app on Honor
     * phones, never asks for: without it the recording and playback notifications (with their pause and stop buttons)
     * stay hidden. Asked only once the microphone is granted, so it never competes with Recorder's own request.
     */
    public static void askForNotifications(Activity activity) {
        if (activity == null || android.os.Build.VERSION.SDK_INT < 33) return;
        if (activity.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) return;
        if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences asked = activity.getSharedPreferences("ungoogled_honor", Context.MODE_PRIVATE);
        if (asked.getBoolean("notifications_asked", false)) return;
        asked.edit().putBoolean("notifications_asked", true).apply();
        activity.requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0x4e4f);
    }
}
