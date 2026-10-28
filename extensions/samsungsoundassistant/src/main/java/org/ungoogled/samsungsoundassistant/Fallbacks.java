package org.ungoogled.samsungsoundassistant;

import android.bluetooth.BluetoothDevice;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * One UI members Sound Assistant needs a real answer for off One UI, from Android's own public calls. Everything
 * else a phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;":
                    try {
                        return ((PackageManager) receiver).getApplicationIcon((String) args[0]);
                    } catch (PackageManager.NameNotFoundException e) {
                        return ((PackageManager) receiver).getDefaultActivityIcon();
                    }
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Landroid/content/pm/ApplicationInfo;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((ApplicationInfo) args[0]);
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/bluetooth/BluetoothDevice;->semGetAlias()Ljava/lang/String;":
                    return ((BluetoothDevice) receiver).getAlias();
                case "Landroid/media/AudioManager;->semGetFineVolume(I)I":
                    return FineVolume.get((android.media.AudioManager) receiver, (Integer) args[0]);
                case "Landroid/media/AudioManager;->semSetFineVolume(III)V":
                    FineVolume.set((android.media.AudioManager) receiver, (Integer) args[0], (Integer) args[1], (Integer) args[2]);
                    return null;
            }
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }
}
