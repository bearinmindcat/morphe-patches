package org.ungoogled.samsungweather;

import android.os.Process;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

/**
 * One UI members (and newer-Android ones, see Api) Weather needs a real answer for where the phone lacks them, from
 * Android's own public calls. Everything else a phone lacks answers "absent" (see OneUi).
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
                case "Landroid/telephony/TelephonyManager;->semGetDataState(I)I":
                    return ((TelephonyManager) receiver).getDataState();
                case "Landroid/app/Activity;->getLaunchedFromUid()I":
                    // Android 14's; below it the launcher is unknown, which Weather treats as not trusted.
                    return Process.INVALID_UID;
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
