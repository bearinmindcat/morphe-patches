package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Clock uses, answering "absent" off ColorOS (no OPPO code). */
public class UserHandle {
    public UserHandle(android.os.UserHandle p0) {
    }
    public static int USER_CURRENT = -2;
    public int getIdentifier() {
        return android.os.Process.myUserHandle().hashCode();
    }
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
}
