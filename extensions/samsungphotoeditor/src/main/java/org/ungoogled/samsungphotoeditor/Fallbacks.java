package org.ungoogled.samsungphotoeditor;

import android.app.Activity;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;

/**
 * One UI members Photo Editor needs a real answer for off One UI, from Android's own public calls. Everything else a
 * phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // A UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    // One UI's haptic patterns: Android's own light tick (the caller vibrates whatever it gets).
                    return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
                case "Landroid/app/Activity;->semIsResumed()Z":
                    return isResumed((Activity) receiver);
            }
        } catch (RuntimeException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            // All users / the owner: an app can only reach its own user.
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
            case "Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;":
                return Process.myUserHandle();
        }
        return NONE;
    }

    /** Activity's own (hidden, but open to apps) isResumed(); where refused, whether its window has focus. */
    private static boolean isResumed(Activity activity) {
        try {
            java.lang.reflect.Method method = Activity.class.getDeclaredMethod("isResumed");
            method.setAccessible(true);
            return (Boolean) method.invoke(activity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return activity.hasWindowFocus();
        }
    }
}
