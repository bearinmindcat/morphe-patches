package org.ungoogled.samsungreminder;

import android.content.Context;
import android.media.AudioManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.concurrent.Executor;

/**
 * Reminder's phone-state calls. On One UI it holds READ_PHONE_STATE as a system app; elsewhere it does not, and the
 * calls throw. The call state then comes from the audio mode, which needs no permission, and listening is skipped.
 */
public final class Phone {
    private Phone() {
    }

    public static int getCallState(TelephonyManager tm) {
        try {
            return tm.getCallState();
        } catch (SecurityException e) {
            try {
                AudioManager audio = (AudioManager) Reminders.context().getSystemService(Context.AUDIO_SERVICE);
                switch (audio.getMode()) {
                    case AudioManager.MODE_RINGTONE:
                        return TelephonyManager.CALL_STATE_RINGING;
                    case AudioManager.MODE_IN_CALL:
                    case AudioManager.MODE_IN_COMMUNICATION:
                    case AudioManager.MODE_CALL_SCREENING:
                        return TelephonyManager.CALL_STATE_OFFHOOK;
                    default:
                        return TelephonyManager.CALL_STATE_IDLE;
                }
            } catch (RuntimeException again) {
                return TelephonyManager.CALL_STATE_IDLE;
            }
        }
    }

    public static void registerTelephonyCallback(TelephonyManager tm, Executor executor, TelephonyCallback callback) {
        try {
            tm.registerTelephonyCallback(executor, callback);
        } catch (SecurityException e) {
            // Calls do not interrupt the alert sound then.
        }
    }

    public static void unregisterTelephonyCallback(TelephonyManager tm, TelephonyCallback callback) {
        try {
            tm.unregisterTelephonyCallback(callback);
        } catch (RuntimeException e) {
            // It was never registered.
        }
    }
}
