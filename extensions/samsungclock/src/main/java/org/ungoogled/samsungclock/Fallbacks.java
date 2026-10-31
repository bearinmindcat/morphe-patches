package org.ungoogled.samsungclock;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Process;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * One UI members Clock needs a real answer for off One UI, from Android's own calls. Everything else a phone lacks
 * answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /** Vibration patterns (off, on, off, on ... ms) for One UI's numbered ones, so the choices still differ. */
    private static final long[][] PATTERNS = {
            {0, 500, 500},
            {0, 200, 100, 200, 600},
            {0, 1000, 500},
            {0, 100, 100, 100, 100, 100, 600},
            {0, 300, 200, 700, 500},
    };

    private static Method resumed;

    private Fallbacks() {
    }

    /** One UI's numbered vibration as a plain waveform; repeat is the waveform's own (-1 = once). */
    private static VibrationEffect waveform(int index, int repeat, Object magnitude) {
        if (magnitude != null && "TYPE_TOUCH".equals(String.valueOf(magnitude))) {
            return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        }
        long[] pattern = PATTERNS[Math.floorMod(index, PATTERNS.length)];
        return VibrationEffect.createWaveform(pattern, repeat < 0 ? -1 : 0);
    }

    private static boolean isResumed(Activity activity) {
        try {
            if (resumed == null) {
                resumed = Activity.class.getDeclaredMethod("isResumed");
                resumed.setAccessible(true);
            }
            return (Boolean) resumed.invoke(activity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return activity.hasWindowFocus();
        }
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/VibrationEffect;->semCreateWaveform(II)Landroid/os/VibrationEffect;":
                    return waveform((Integer) args[0], (Integer) args[1], null);
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    return waveform((Integer) args[0], (Integer) args[1], args[2]);
                case "Landroid/media/AudioManager;->semGetRingerModeInternal()I":
                    return ((AudioManager) receiver).getRingerMode();
                case "Landroid/media/Ringtone;->semIsUriValid()Z":
                    return Sounds.valid((android.media.Ringtone) receiver);
                case "Landroid/app/Activity;->semIsResumed()Z":
                    return isResumed((Activity) receiver);
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/content/pm/PackageManager;->semQueryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                    return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], (Integer) args[1]);
                case "Landroid/widget/Toast;->semMakeAction(Landroid/content/Context;Ljava/lang/CharSequence;ILjava/lang/CharSequence;Landroid/view/View$OnClickListener;)Landroid/widget/Toast;":
                    return Toast.makeText((Context) args[0], (CharSequence) args[1], (Integer) args[2]);
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                    return Process.myUid() / 100000;
            }
        } catch (SecurityException | ClassCastException | NullPointerException e) {
            return NONE;
        }
        return NONE;
    }
}
