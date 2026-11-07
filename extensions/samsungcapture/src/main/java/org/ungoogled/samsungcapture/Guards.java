package org.ungoogled.samsungcapture;

import android.content.Context;
import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/** Calls One UI lets its preinstalled Samsung capture make and Android refuses a regular app. */
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
}
