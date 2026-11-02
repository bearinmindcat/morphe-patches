package com.hihonor.android.content;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off MagicOS (no Honor code). */
public class ContextEx {
    public static boolean bindServiceAsUser(android.content.Context p0, android.content.Intent p1, android.content.ServiceConnection p2, int p3, android.os.UserHandle p4) {
        return false;
    }
    public static android.content.Context createCredentialProtectedStorageContext(android.content.Context p0) {
        return p0;
    }
    public static int getUserId(android.content.Context p0) {
        return android.os.Process.myUserHandle().hashCode();
    }
}
