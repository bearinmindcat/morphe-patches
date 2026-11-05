package com.hihonor.android.content.pm;

/**
 * Stand-in (written by hand): MagicOS's view of a user (Android's hidden UserInfo), for the users an installed app can
 * see: its own and its profiles (work profile), see UserManagerEx. MagicOS's cloned-app profile does not exist here.
 */
public class UserInfoEx {
    private final int id;
    private final boolean managed;

    public UserInfoEx() {
        this(android.os.Process.myUserHandle().hashCode(), false);
    }
    public UserInfoEx(int id, boolean managed) {
        this.id = id;
        this.managed = managed;
    }
    public int getUserInfoId() {
        return id;
    }
    public boolean isClonedProfile() {
        return false;
    }
    public boolean isManagedProfile() {
        return managed;
    }
}
