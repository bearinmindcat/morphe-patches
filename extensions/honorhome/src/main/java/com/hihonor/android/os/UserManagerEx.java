package com.hihonor.android.os;

/**
 * Stand-in (written by hand): MagicOS's user manager calls, answered with the users an installed app can see: its own
 * user and that user's profiles (UserManager.getUserProfiles; any profile other than the app's own is a work profile).
 * Creating or removing users and MagicOS's hidden space are not available.
 */
public class UserManagerEx {
    public UserManagerEx() {
    }
    public static com.hihonor.android.content.pm.UserInfoEx createProfileForUser(android.os.UserManager p0, java.lang.String p1, int p2, int p3) {
        return null;
    }
    public static android.os.UserManager get(android.content.Context p0) {
        return p0.getSystemService(android.os.UserManager.class);
    }
    public static java.util.List getProfiles(android.os.UserManager p0, int p1) {
        java.util.List<com.hihonor.android.content.pm.UserInfoEx> out = new java.util.ArrayList<>();
        int own = android.os.Process.myUserHandle().hashCode();
        out.add(new com.hihonor.android.content.pm.UserInfoEx(own, false));
        if (p0 == null) return out;
        try {
            for (android.os.UserHandle user : p0.getUserProfiles()) {
                int id = user.hashCode();
                if (id != own) out.add(new com.hihonor.android.content.pm.UserInfoEx(id, true));
            }
        } catch (RuntimeException e) {
            // Only the app's own user.
        }
        return out;
    }
    public static com.hihonor.android.content.pm.UserInfoEx getUserInfoEx(android.os.UserManager p0, int p1) {
        for (Object info : getProfiles(p0, p1)) {
            if (((com.hihonor.android.content.pm.UserInfoEx) info).getUserInfoId() == p1) return (com.hihonor.android.content.pm.UserInfoEx) info;
        }
        return null;
    }
    public static java.util.List getUsers(android.os.UserManager p0) {
        return getProfiles(p0, android.os.Process.myUserHandle().hashCode());
    }
    public static boolean hasUserRestriction(android.os.UserManager p0, java.lang.String p1, android.os.UserHandle p2) {
        return p0 != null && p0.hasUserRestriction(p1);
    }
    public static boolean isHwHiddenSpace(com.hihonor.android.content.pm.UserInfoEx p0) {
        return false;
    }
    public boolean isUserInfoClonedProfile(com.hihonor.android.content.pm.UserInfoEx p0) {
        return false;
    }
    public static boolean removeUser(android.os.UserManager p0, int p1) {
        return false;
    }
}
