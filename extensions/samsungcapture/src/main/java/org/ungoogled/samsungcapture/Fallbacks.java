package org.ungoogled.samsungcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;

/**
 * One UI members Samsung capture needs a real answer for off One UI, from Android's own calls. Everything else a phone
 * lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            // The same user is the only one an app may reach.
            case "Landroid/content/Context;->semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;":
            case "Landroid/app/Activity;->semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;":
                return ((Context) receiver).startService((Intent) args[0]);
            case "Landroid/content/Context;->semBindServiceAsUser(Landroid/content/Intent;Landroid/content/ServiceConnection;ILandroid/os/UserHandle;)Z":
            case "Landroid/app/Activity;->semBindServiceAsUser(Landroid/content/Intent;Landroid/content/ServiceConnection;ILandroid/os/UserHandle;)Z":
                try {
                    return ((Context) receiver).bindService((Intent) args[0], (ServiceConnection) args[1], (Integer) args[2]);
                } catch (SecurityException e) {
                    return Boolean.FALSE;
                }
            case "Landroid/content/Context;->semCreatePackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;":
                try {
                    return ((Context) receiver).createPackageContext((String) args[0], (Integer) args[1]);
                } catch (Exception e) {
                    return null;
                }
            case "Landroid/media/projection/MediaProjectionManager;->semGetMediaProjection()Landroid/media/projection/MediaProjection;":
                return Projection.get((MediaProjectionManager) receiver, 0);
            case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case "Landroid/os/UserHandle;->semGetMyUserId()I":
            case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                return Process.myUid() / 100000;
            case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                return Binder.getCallingUid() / 100000;
            case "Landroid/os/UserHandle;->semGetIdentifier()I":
                // UserHandle's hash code is its user id.
                return receiver.hashCode();
            case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                return Process.myUserHandle();
            case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
            case "Landroid/os/UserHandle;->SEM_CURRENT:Landroid/os/UserHandle;":
            case "Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;":
                return Process.myUserHandle();
        }
        return NONE;
    }
}
