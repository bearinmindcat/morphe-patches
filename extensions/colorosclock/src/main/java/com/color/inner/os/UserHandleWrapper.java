package com.color.inner.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Clock uses, answering "absent" off ColorOS (no OPPO code). */
public class UserHandleWrapper {
    public static android.os.UserHandle CURRENT = android.os.Process.myUserHandle();
    public static android.os.UserHandle OWNER = android.os.UserHandle.getUserHandleForUid(0);
    public static android.os.UserHandle createUserHandle(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
    public static int getIdentifier(android.os.UserHandle p0) {
        return p0 == null ? 0 : p0.hashCode();
    }
    public static int getUserId(int p0) {
        return p0 / 100000;
    }
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
}
