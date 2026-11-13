package org.ungoogled.samsungeditorlite;

import android.content.Context;
import android.media.AudioManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Every Context.getSystemService(String) in Editor Lite comes here (for the application, which the call-state answers
 * below need); Editor Lite asks for no One UI service.
 */
public final class Services {
    private static Context app;
    private static final Map<TelephonyCallback, AudioManager.OnModeChangedListener> CALL_LISTENERS = new WeakHashMap<>();

    private Services() {
    }

    public static Object get(Context context, String name) {
        if (app == null && context != null) app = context.getApplicationContext();
        return context.getSystemService(name);
    }

    /**
     * Editor Lite pauses its preview for phone calls by listening to the call state, which needs READ_PHONE_STATE
     * (One UI's own build has the privileged phone permission). Without it the same answer comes from the audio mode: ringing, or
     * in a call.
     */
    public static void registerTelephonyCallback(TelephonyManager manager, Executor executor, TelephonyCallback callback) {
        try {
            manager.registerTelephonyCallback(executor, callback);
        } catch (SecurityException e) {
            if (!(callback instanceof TelephonyCallback.CallStateListener) || app == null) return;
            AudioManager audio = app.getSystemService(AudioManager.class);
            TelephonyCallback.CallStateListener listener = (TelephonyCallback.CallStateListener) callback;
            AudioManager.OnModeChangedListener modes = mode -> listener.onCallStateChanged(callState(mode));
            synchronized (CALL_LISTENERS) {
                CALL_LISTENERS.put(callback, modes);
            }
            audio.addOnModeChangedListener(executor, modes);
        }
    }

    public static void unregisterTelephonyCallback(TelephonyManager manager, TelephonyCallback callback) {
        AudioManager.OnModeChangedListener modes;
        synchronized (CALL_LISTENERS) {
            modes = CALL_LISTENERS.remove(callback);
        }
        if (modes != null && app != null) {
            app.getSystemService(AudioManager.class).removeOnModeChangedListener(modes);
            return;
        }
        manager.unregisterTelephonyCallback(callback);
    }

    /** TelephonyManager.getCallState() needs READ_PHONE_STATE; without it, the audio mode says the same. */
    public static int getCallState(TelephonyManager manager) {
        try {
            return manager.getCallState();
        } catch (SecurityException e) {
            return app == null ? TelephonyManager.CALL_STATE_IDLE
                    : callState(app.getSystemService(AudioManager.class).getMode());
        }
    }

    private static int callState(int mode) {
        switch (mode) {
            case AudioManager.MODE_RINGTONE:
                return TelephonyManager.CALL_STATE_RINGING;
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
            case AudioManager.MODE_CALL_SCREENING:
            case AudioManager.MODE_CALL_REDIRECT:
            case AudioManager.MODE_COMMUNICATION_REDIRECT:
                return TelephonyManager.CALL_STATE_OFFHOOK;
            default:
                return TelephonyManager.CALL_STATE_IDLE;
        }
    }
}
