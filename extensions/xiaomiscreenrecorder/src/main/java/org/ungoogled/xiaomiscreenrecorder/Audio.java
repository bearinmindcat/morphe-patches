package org.ungoogled.xiaomiscreenrecorder;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

/**
 * The recorder's app-audio capture as a regular app may do it. HyperOS's preinstalled recorder holds the system's
 * phone-state and call-capture permissions; off HyperOS it holds neither, and each missing one made the whole
 * recording fail before the first frame.
 */
public final class Audio {
    private Audio() {
    }

    /**
     * TelephonyManager.listen: the recorder watches the call state (to drop app audio during a call), which needs
     * READ_PHONE_STATE since Android 12; without it, it records without that watch instead of failing.
     */
    @SuppressWarnings("deprecation")
    public static void listen(TelephonyManager telephony, PhoneStateListener listener, int events) {
        try {
            telephony.listen(listener, events);
        } catch (SecurityException e) {
            android.util.Log.w("ScreenRecorderPort", "no call-state watch: " + e.getMessage());
        }
    }

    /**
     * In some regions the recorder also captures call audio (USAGE_VOICE_COMMUNICATION), which Android allows only
     * with CAPTURE_VOICE_COMMUNICATION_OUTPUT; asking for it without that permission fails the app-audio capture.
     */
    public static int voiceCalls(Context context, int wanted) {
        return context.checkSelfPermission("android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT")
                == PackageManager.PERMISSION_GRANTED ? wanted : 0;
    }
}
