package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Calendar uses, answering "absent" off MagicOS (no Honor code). */
public class FileUtilsEx {
    public static int getSIRWXG() {
        return 070;
    }
    public static int getSIRWXU() {
        return 0700;
    }
    public static int getSIXOTH() {
        return 01;
    }
    public static int setPermissions(java.lang.String p0, int p1, int p2, int p3) {
        try { android.system.Os.chmod(p0, p1); return 0; } catch (Exception e) { return -1; }
    }
}
