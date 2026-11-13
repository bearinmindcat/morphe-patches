package org.ungoogled.samsungvideolibrary;

import android.app.Activity;
import android.content.ContentResolver;
import android.media.MediaPlayer;
import android.os.Process;
import android.os.storage.StorageVolume;
import android.provider.Settings;

import java.io.File;

/**
 * One UI members Video Library needs a real answer for off One UI, from Android's own public calls. Everything else a
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
                case "Landroid/app/Activity;->semOverridePendingTransition(II)V":
                    ((Activity) receiver).overridePendingTransition((Integer) args[0], (Integer) args[1]);
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
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/media/MediaPlayer;->semSeekTo(II)V":
                    ((MediaPlayer) receiver).seekTo((Integer) args[0]);
                    return null;
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
