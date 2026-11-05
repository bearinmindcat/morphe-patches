package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Home uses, answering "absent" off MagicOS (no Honor code). */
public class UserHandleEx {
    public static android.os.UserHandle OWNER = android.os.UserHandle.getUserHandleForUid(0);
    public static android.os.UserHandle SYSTEM;
    public static int getIdentifier(android.os.UserHandle p0) {
        return p0 == null ? 0 : p0.hashCode();
    }
    public static android.os.UserHandle getUserHandle(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
    public static int getUserId(int p0) {
        return p0 / 100000;
    }
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
    public static android.os.UserHandle of(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
}
