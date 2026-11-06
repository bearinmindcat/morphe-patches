package org.ungoogled.samsungonehand;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;

/**
 * The One UI members One Hand Operation + needs a real answer for off One UI, from Android's own public calls.
 * Everything else a phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /** One UI's fine volume scale (the app's volume slider runs 0..150). */
    private static final int FINE = 150;

    private Fallbacks() {
    }

    private static int userId() {
        return Process.myUid() / 100000;
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                    return userId();
                case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                    return UserHandle.getUserHandleForUid((Integer) args[0] * 100000);
                case "Landroid/app/KeyguardManager;->semIsKeyguardShowingAndNotOccluded()Z":
                    return ((KeyguardManager) receiver).isKeyguardLocked();
                case "Landroid/media/AudioManager;->semGetRingerModeInternal()I":
                    return ((AudioManager) receiver).getRingerMode();
                case "Landroid/media/AudioManager;->semGetFineVolume(I)I": {
                    AudioManager audio = (AudioManager) receiver;
                    int stream = (Integer) args[0];
                    return Math.round(audio.getStreamVolume(stream) * (float) FINE / audio.getStreamMaxVolume(stream));
                }
                case "Landroid/media/AudioManager;->semSetFineVolume(III)V": {
                    AudioManager audio = (AudioManager) receiver;
                    int stream = (Integer) args[0];
                    int index = Math.round((Integer) args[1] * audio.getStreamMaxVolume(stream) / (float) FINE);
                    index = Math.max(audio.getStreamMinVolume(stream), Math.min(audio.getStreamMaxVolume(stream), index));
                    audio.setStreamVolume(stream, index, (Integer) args[2]);
                    return null;
                }
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/provider/Settings$System;->semPutIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z": {
                    Context c = OneHand.context();
                    if (c == null || !Actions.canWriteSettings(c)) return false;
                    return Settings.System.putInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                }
                case "Landroid/os/PowerManager;->semGetMinimumScreenBrightnessSetting()I":
                    return 1;
                case "Landroid/os/PowerManager;->semGetMaximumScreenBrightnessSetting()I":
                    return 255;
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
                case "Landroid/view/HapticFeedbackConstants;->semGetVibrationIndex(I)I":
                    return HapticFeedbackConstants.CLOCK_TICK;
                case "Landroid/content/pm/PackageManager;->semGetActivityIconForIconTray(Landroid/content/ComponentName;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getActivityIcon((ComponentName) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((String) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Landroid/content/pm/ApplicationInfo;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((ApplicationInfo) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetDrawableForIconTray(Landroid/graphics/drawable/Drawable;I)Landroid/graphics/drawable/Drawable;":
                    return (Drawable) args[0];
                case "Landroid/content/Context;->semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;":
                    return ((Context) receiver).startService((Intent) args[0]);
            }
        } catch (SecurityException | ClassCastException | NullPointerException | IllegalArgumentException
                 | PackageManager.NameNotFoundException e) {
            return NONE;
        }
        return NONE;
    }

    static Object construct(String key, Object[] args) {
        return NONE;
    }

    static Object get(String key) {
        if ("Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;".equals(key)) return Process.myUserHandle();
        return NONE;
    }
}
