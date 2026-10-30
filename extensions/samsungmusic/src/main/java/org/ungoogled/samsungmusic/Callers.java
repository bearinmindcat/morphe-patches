package org.ungoogled.samsungmusic;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Music's media browser (library browsing for Bluetooth and the system's media controls) answers only a list of
 * packages signed with Samsung's or Google's keys. Off One UI the system's own Bluetooth and SystemUI are signed with
 * the ROM's platform key instead; a caller signed like the system ("android") is let in too.
 */
public final class Callers {
    private Callers() {
    }

    public static boolean system(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            return context.getPackageManager().checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
