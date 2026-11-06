package org.ungoogled.samsungemail;

import android.content.ContentResolver;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.storage.StorageVolume;
import android.provider.Settings;

import java.io.File;

/**
 * One UI members Email needs a real answer for off One UI, from Android's own calls. Everything else a phone lacks
 * answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private static final String DEVICE_POLICY = "Landroid/app/admin/DevicePolicyManager;->";

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        // One UI's Exchange IT policies on DevicePolicyManager ("allow POP/IMAP accounts", "allow Wi-Fi", ...): with no
        // such policy everything is allowed, which "absent" (false) would turn into "not allowed" (no POP/IMAP setup).
        if (key.startsWith(DEVICE_POLICY + "semGetAllow")) {
            // Bluetooth mode 2 = allowed.
            return key.endsWith(")Z") ? Boolean.TRUE : (Object) 2;
        }
        if (key.startsWith(DEVICE_POLICY + "semIsSimplePasswordEnabled")) return Boolean.TRUE;
        switch (key) {
            case "Landroid/os/storage/StorageVolume;->semGetPath()Ljava/lang/String;": {
                File directory = ((StorageVolume) receiver).getDirectory();
                return directory == null ? null : directory.getPath();
            }
            case "Landroid/os/storage/StorageVolume;->semGetSubSystem()Ljava/lang/String;":
                return ((StorageVolume) receiver).isRemovable() ? "sd" : "fuse";
            case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case "Landroid/os/UserHandle;->semGetMyUserId()I":
                return Process.myUid() / 100000;
            case "Landroid/os/UserHandle;->semGetUserId(I)I":
                return (Integer) args[0] / 100000;
            case "Landroid/os/UserHandle;->semGetIdentifier()I":
                // UserHandle's hash code is its user id.
                return receiver.hashCode();
            case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
            case "Landroid/os/UserHandle;->SEM_CURRENT:Landroid/os/UserHandle;":
                // Android's own UserHandle.ALL / CURRENT where it may be read; else this user.
                try {
                    return UserHandle.class.getField(key.contains("SEM_ALL") ? "ALL" : "CURRENT").get(null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return Process.myUserHandle();
                }
        }
        return NONE;
    }
}
