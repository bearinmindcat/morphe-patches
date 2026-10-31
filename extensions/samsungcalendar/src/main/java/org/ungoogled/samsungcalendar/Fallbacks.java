package org.ungoogled.samsungcalendar;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;

/**
 * One UI members Calendar needs a real answer for off One UI, from Android's own public calls. Everything else a
 * phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    private static UserHandle user(int id) {
        return UserHandle.getUserHandleForUid(id * 100000);
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/UserManager;->semGetUsers()Ljava/util/List;":
                    return new ArrayList<>();
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    // One UI's own haptic patterns: a click for touch feedback, a pulse for alerts (repeating when asked).
                    if (args[2] != null && "TYPE_TOUCH".equals(args[2].toString())) {
                        return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
                    }
                    int repeat = (Integer) args[1];
                    return VibrationEffect.createWaveform(new long[]{0, 800, 800}, repeat >= 0 ? 0 : -1);
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
            }
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                return user(-1);
        }
        return NONE;
    }
}
