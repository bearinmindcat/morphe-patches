package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Calendar uses, answering "absent" off MagicOS (no Honor code). */
public class UserHandleEx {
    public static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }
    public static android.os.UserHandle of(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
}
