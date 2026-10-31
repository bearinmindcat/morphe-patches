package org.ungoogled.samsungvideoplayer;

import android.content.Context;
import android.media.AudioManager;
import android.os.UserManager;
import android.telephony.TelephonyCallback;
import android.widget.RemoteViews;
import android.telephony.TelephonyManager;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import com.samsung.android.sepunion.SemEventDelegationManager;

/**
 * Every Context.getSystemService(String) in Video Player comes here. Off One UI Samsung's own services are absent
 * (null); the one Video Player uses without checking gets a stand-in that does nothing.
 */
public final class Services {
    private static Context app;
    private static final Map<TelephonyCallback, AudioManager.OnModeChangedListener> CALL_LISTENERS = new WeakHashMap<>();

    private Services() {
    }

    public static Object get(Context context, String name) {
        if (app == null && context != null) app = context.getApplicationContext();
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        switch (name) {
            case "semeventdelegator":
                return new SemEventDelegationManager();
        }
        return null;
    }

    /** UserManager.getUserCount() needs MANAGE_USERS, which only One UI's own build of Video Player gets. */
    public static int getUserCount(UserManager manager) {
        try {
            return manager.getUserCount();
        } catch (SecurityException e) {
            return 1;
        }
    }

    /**
     * Video Player pauses for phone calls by listening to the call state, which needs READ_PHONE_STATE (One UI's own
     * build has the privileged phone permission). Without it the same answer comes from the audio mode: ringing, or
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

    /**
     * The notification's title marquee is started with setInt(id, "setSelected", ...), which only One UI's system UI
     * accepts; elsewhere the notification cannot be drawn and the system kills the app. That one call is dropped.
     */
    public static void setInt(RemoteViews views, int viewId, String method, int value) {
        if ("setSelected".equals(method)) return;
        views.setInt(viewId, method, value);
    }
}
