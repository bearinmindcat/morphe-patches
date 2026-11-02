package com.color.inner.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Weather uses, answering "absent" off ColorOS (no OPPO code). */
public class UserManagerWrapper {
    public static boolean canShowMultiUserEntry(android.content.Context p0) {
        return false;
    }
    public static boolean canShowMultiUserEntry(android.content.Context p0, int p1) {
        return false;
    }
    public static com.color.inner.content.pm.UserInfoWrapper createUser(android.os.UserManager p0, java.lang.String p1, int p2) {
        return new com.color.inner.content.pm.UserInfoWrapper();
    }
    public static com.color.inner.content.pm.UserInfoWrapper getUserInfo(android.os.UserManager p0, int p1) {
        return null;
    }
    public static java.util.List getUsers(android.content.Context p0) {
        return new java.util.ArrayList<>();
    }
    public static boolean isUserIDExist(android.content.Context p0, int p1) {
        return false;
    }
}
