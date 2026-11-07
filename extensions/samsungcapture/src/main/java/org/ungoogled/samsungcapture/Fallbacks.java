package org.ungoogled.samsungcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Process;
import android.os.VibrationEffect;

/**
 * One UI members Samsung capture needs a real answer for off One UI, from Android's own calls (call, get), and the
 * members it never uses even where the phone has them (always). Everything else a phone lacks answers "absent" (see
 * OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private static final String START_SERVICE = "semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;";
    private static final String BIND_SERVICE = "semBindServiceAsUser(Landroid/content/Intent;Landroid/content/ServiceConnection;ILandroid/os/UserHandle;)Z";

    private Fallbacks() {
    }

    /**
     * Members only Samsung's own, platform-signed copy may use, answered this copy's way on every phone, the way the
     * port runs where One UI is absent. On a Galaxy phone they would be refused (SecurityException), at once or later:
     * Samsung's window flags make the floating controls' addView fail, the concurrent-capture flag the recorder's
     * build(), the process-importance call needs SET_PROCESS_LIMIT, the screen capture without asking
     * MANAGE_MEDIA_PROJECTION, the per-user calls INTERACT_ACROSS_USERS. NONE: not one of them.
     */
    static Object always(Object receiver, String key, Object[] args) {
        switch (key) {
            case "Landroid/view/WindowManager$LayoutParams;->semAddExtensionFlags(I)V":
            case "Landroid/view/WindowManager$LayoutParams;->semAddPrivateFlags(I)V":
            case "Landroid/view/WindowManager$LayoutParams;->semSetReceiveInsetsIgnoringZOrder(Z)V":
            case "Landroid/app/ActivityManager;->semSetProcessImportant(Landroid/os/IBinder;IZ)V":
            case "Landroid/content/Intent;->semSetLaunchOverTargetTask(IZ)V":
                return null;
            case "Landroid/media/AudioRecord$Builder;->semAllowConcurrentCapture(Z)Landroid/media/AudioRecord$Builder;":
            case "Landroid/app/ActivityOptions;->semSetPopOverOptions([I[I[Landroid/graphics/Point;[I)Landroid/app/ActivityOptions;":
                return receiver;
            case "Landroid/media/projection/MediaProjectionManager;->semGetMediaProjection()Landroid/media/projection/MediaProjection;":
                return Projection.get((MediaProjectionManager) receiver, 0);
            // The same user is the only one an app may reach.
            case "Landroid/content/Context;->" + START_SERVICE:
            case "Landroid/app/Activity;->" + START_SERVICE:
                return Guards.startService((Context) receiver, (Intent) args[0]);
            case "Landroid/content/Context;->" + BIND_SERVICE:
            case "Landroid/app/Activity;->" + BIND_SERVICE:
                return Guards.bindService((Context) receiver, (Intent) args[0], (ServiceConnection) args[1], (Integer) args[2]);
            case "Landroid/content/Context;->semCreatePackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;":
                try {
                    return ((Context) receiver).createPackageContext((String) args[0], (Integer) args[1]);
                } catch (Exception e) {
                    return null;
                }
            // Through LocalSettings, which also holds the settings Android refused to store for this copy.
            case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                return LocalSettings.systemGetInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
        }
        return NONE;
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
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
