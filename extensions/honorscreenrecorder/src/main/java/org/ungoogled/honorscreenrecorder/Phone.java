package org.ungoogled.honorscreenrecorder;

import android.content.Context;
import android.media.AudioManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Honor Screen recording's phone-call checks (patches/.../honorscreenrecorder sends its TelephonyManager getCallState /
 * listen calls here). On an Honor phone the recorder is a system app reading the call state with
 * READ_PRIVILEGED_PHONE_STATE; any other app needs READ_PHONE_STATE, which it never asks for, so the recording service
 * died at start with a SecurityException. The call state then comes from the audio mode (a phone call / ringing), and a
 * call-state listener follows audio mode changes: a recording still ends when a call comes in, and none starts during
 * a call. Calls in other apps (Android's "in communication" mode) do not count, as they don't on an Honor phone.
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
}
