package org.ungoogled.samsungeditorlite;

import android.app.Activity;
import android.os.Process;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.storage.StorageVolume;

import java.io.File;

/**
 * One UI members Editor Lite needs a real answer for off One UI, from Android's own public calls. Everything else a
 * phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/app/Activity;->semIsResumed()Z":
                    return isResumed((Activity) receiver);
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserManager;->semIsManagedProfile()Z":
                    return ((UserManager) receiver).isManagedProfile();
                case "Landroid/os/storage/StorageVolume;->semGetPath()Ljava/lang/String;": {
                    File directory = ((StorageVolume) receiver).getDirectory();
                    return directory != null ? directory.getPath() : null;
                }
                case "Landroid/os/storage/StorageVolume;->semGetSubSystem()Ljava/lang/String;": {
                    StorageVolume volume = (StorageVolume) receiver;
                    return volume.isPrimary() ? "fuse" : volume.isRemovable() ? "sd" : "private";
                }
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    // One UI's haptic patterns: Android's own light tick (the caller vibrates whatever it gets).
                    return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
        } catch (RuntimeException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        return NONE;
    }

    /**
     * Activity's own resumed flag: its (hidden, but open to apps) isResumed(); where a phone refuses that, whether the
     * activity's window has focus.
     */
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
