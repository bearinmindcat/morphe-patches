package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class UserHandle {
    public static android.os.UserHandle OWNER = android.os.UserHandle.getUserHandleForUid(0);
    public static int USER_SYSTEM = 0;
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
}
