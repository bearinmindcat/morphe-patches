package org.ungoogled.coloroscompass;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Compass asks for every permission its manifest names that isn't granted yet. Two of them are OPPO's own
 * (com.oplus.permission.safe.PRIVATE, oplus.permission.OPLUS_COMPONENT_SAFE), which only ColorOS defines: elsewhere
 * Android answers such a request "denied" at once without asking, and Compass asked again on every resume -- an
 * endless loop of permission screens that kept pausing the compass. A permission this phone doesn't define counts as
 * granted here (nothing can grant it, and what it guards -- ColorOS's system components -- isn't there either).
 */
public final class Permissions {
    private Permissions() {}

    public static int check(Context context, String permission) {
        int state = context.checkSelfPermission(permission);
        if (state == PackageManager.PERMISSION_GRANTED || permission == null) return state;
        try {
            context.getPackageManager().getPermissionInfo(permission, 0);
            return state;
        } catch (PackageManager.NameNotFoundException absent) {
            return PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return state;
        }
    }
}
