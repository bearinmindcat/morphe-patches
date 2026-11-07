package org.ungoogled.samsungcapture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.UserHandle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Calls One UI lets its preinstalled Samsung capture make and Android refuses a regular app. On a Galaxy phone
 * Samsung's services (AI, Bixby, OCR, capture plugin, ...) are there but bound to Samsung's signature: starting or
 * binding one throws SecurityException for this copy, which is now the same as their being absent.
 */
public final class Guards {
    private Guards() {
    }

    /**
     * Context.sendBroadcast: CLOSE_SYSTEM_DIALOGS (sent before the editor and the recorder's windows open) needs a
     * system permission since Android 12 and throws for an app; the shade closes by itself when they open.
     */
    public static void sendBroadcast(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) return;
        try {
            context.sendBroadcast(intent);
        } catch (SecurityException e) {
            Log.w(Projection.TAG, "broadcast refused: " + intent, e);
        }
    }

    public static void sendBroadcast(Context context, Intent intent, String permission) {
        if (intent != null && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) return;
        try {
            context.sendBroadcast(intent, permission);
        } catch (SecurityException e) {
            Log.w(Projection.TAG, "broadcast refused: " + intent, e);
        }
    }

    /**
     * TelephonyManager.listen: the recorder stops for a phone call by watching the call state, which needs the phone
     * permission One UI grants it; without it the watch is skipped (Android silences call audio in recordings anyway).
     */
    public static void listen(TelephonyManager telephony, PhoneStateListener listener, int events) {
        try {
            telephony.listen(listener, events);
        } catch (SecurityException e) {
            Log.w(Projection.TAG, "call state not watched: " + e.getMessage());
        }
    }

    /** TelephonyManager.getCallState: "idle" where the phone permission is missing. */
    @SuppressWarnings("deprecation")
    public static int getCallState(TelephonyManager telephony) {
        try {
            return telephony.getCallState();
        } catch (SecurityException e) {
            return TelephonyManager.CALL_STATE_IDLE;
        }
    }

    private static void refused(String what, Intent intent, SecurityException e) {
        ComponentName component = intent == null ? null : intent.getComponent();
        Log.w(Projection.TAG, what + " refused: " + (component != null ? component.flattenToShortString()
                : intent != null ? intent.getAction() + " " + intent.getPackage() : null) + ": " + e.getMessage());
    }

    public static ComponentName startService(Context context, Intent intent) {
        try {
            return context.startService(intent);
        } catch (SecurityException e) {
            refused("startService", intent, e);
            return null;
        }
    }

    public static ComponentName startForegroundService(Context context, Intent intent) {
        try {
            return context.startForegroundService(intent);
        } catch (SecurityException e) {
            refused("startForegroundService", intent, e);
            return null;
        }
    }

    public static boolean bindService(Context context, Intent intent, ServiceConnection connection, int flags) {
        try {
            return context.bindService(intent, connection, flags);
        } catch (SecurityException e) {
            refused("bindService", intent, e);
            return false;
        }
    }

    public static boolean bindService(Context context, Intent intent, int flags, java.util.concurrent.Executor executor,
                                      ServiceConnection connection) {
        try {
            return context.bindService(intent, flags, executor, connection);
        } catch (SecurityException e) {
            refused("bindService", intent, e);
            return false;
        }
    }

    /** Context.bindServiceAsUser (hidden): this copy's own user is the only one it may bind in. */
    public static boolean bindServiceAsUser(Context context, Intent intent, ServiceConnection connection, int flags,
                                            UserHandle user) {
        return bindService(context, intent, connection, flags);
    }
}
