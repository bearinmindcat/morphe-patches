package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off MagicOS (no Honor code). */
public class UserHandleEx {
    public static android.os.UserHandle getUserHandle(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
    public static int getUserId(int p0) {
        return p0 / 100000;
    }
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
}
