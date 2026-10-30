package org.ungoogled.samsungmyfiles;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;

import java.util.ArrayList;

/**
 * One UI members My Files needs a real answer for off One UI, from Android's own public calls. Everything else a
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
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                    return user((Integer) args[0]);
                case "Landroid/os/UserManager;->semGetUsers()Ljava/util/List;":
                    return new ArrayList<>();
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Landroid/content/pm/ApplicationInfo;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((ApplicationInfo) args[0]);
                case "Landroid/content/Context;->semCreatePackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;":
                    return ((Context) receiver).createPackageContext((String) args[0], (Integer) args[1]);
            }
        } catch (PackageManager.NameNotFoundException | SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;":
                return user(0);
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                return user(-1);
        }
        return NONE;
    }
}
