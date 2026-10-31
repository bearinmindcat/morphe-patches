package org.ungoogled.samsungvideoplayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.io.File;

/**
 * One UI members Video Player needs a real answer for off One UI, from Android's own public calls. Everything else a
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
                case "Landroid/app/Activity;->semConvertToTranslucent(Landroid/app/Activity$SemTranslucentConversionListener;)Z": {
                    boolean converted = ((Activity) receiver).setTranslucent(true);
                    translucentDone(args[0], converted);
                    return converted;
                }
                case "Landroid/app/Activity;->semConvertFromTranslucent(Z)V":
                    ((Activity) receiver).setTranslucent(false);
                    return null;
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/storage/StorageVolume;->semGetPath()Ljava/lang/String;": {
                    File directory = ((StorageVolume) receiver).getDirectory();
                    return directory != null ? directory.getPath() : null;
                }
                case "Landroid/os/storage/StorageVolume;->semGetSubSystem()Ljava/lang/String;": {
                    StorageVolume volume = (StorageVolume) receiver;
                    return volume.isPrimary() ? "fuse" : volume.isRemovable() ? "sd" : "private";
                }
                case "Landroid/os/PowerManager;->semGetMaximumScreenBrightnessSetting()I":
                    return 255;
                case "Landroid/os/PowerManager;->semGetMinimumScreenBrightnessSetting()I":
                    return 1;
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/view/accessibility/AccessibilityManager;->semIsScreenReaderEnabled()Z":
                    return ((AccessibilityManager) receiver).isTouchExplorationEnabled();
                case "Landroid/app/Service;->semRegisterReceiverAsUser(Landroid/content/BroadcastReceiver;Landroid/os/UserHandle;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;":
                    // One UI's call registers for every user; an app can only listen as itself.
                    return ((Context) receiver).registerReceiver((BroadcastReceiver) args[0], (IntentFilter) args[2],
                            (String) args[3], (Handler) args[4], Context.RECEIVER_EXPORTED);
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

    /** Tells a translucent-conversion listener it is done, after the conversion (as One UI calls it back). */
    private static void translucentDone(Object listener, boolean converted) {
        if (!(listener instanceof android.app.Activity$SemTranslucentConversionListener)) return;
        new Handler(Looper.getMainLooper()).post(() ->
                ((android.app.Activity$SemTranslucentConversionListener) listener).onTranslucentConversionCompleted(converted));
    }
}
