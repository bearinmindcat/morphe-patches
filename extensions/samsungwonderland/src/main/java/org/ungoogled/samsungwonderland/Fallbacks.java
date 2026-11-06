package org.ungoogled.samsungwonderland;

import android.app.KeyguardManager;
import android.os.Process;
import android.view.HapticFeedbackConstants;

/** The One UI members Wonderland needs a real answer for off One UI, from Android's own public calls. */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/app/KeyguardManager;->semIsKeyguardShowingAndNotOccluded()Z":
                    return ((KeyguardManager) receiver).isKeyguardLocked();
                case "Landroid/view/HapticFeedbackConstants;->semGetVibrationIndex(I)I":
                    // One UI's haptic patterns: Android's light tick.
                    return HapticFeedbackConstants.CLOCK_TICK;
            }
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object construct(String key, Object[] args) {
        return NONE;
    }

    static Object get(String key) {
        return NONE;
    }
}
