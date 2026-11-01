package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Clock uses, answering "absent" off MagicOS (no Honor code). */
public class UserHandleEx {
    public static android.os.UserHandle OWNER = android.os.UserHandle.getUserHandleForUid(0);
    public static int getIdentifier(android.os.UserHandle p0) {
        return p0 == null ? 0 : p0.hashCode();
    }
    public static int getUserId(int p0) {
        return p0 / 100000;
    }
    public static android.os.UserHandle of(int p0) {
        return android.os.UserHandle.getUserHandleForUid(p0 * 100000);
    }
}
