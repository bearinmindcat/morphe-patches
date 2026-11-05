package com.hihonor.android.hnsystemmanager;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Home uses, answering "absent" off MagicOS (no Honor code). */
public class HsmSecurityProxy {
    public static com.hihonor.android.hnsystemmanager.HsmSecurityProxy.MaliciousAppInfo getMaliciousAppInfo(java.lang.String p0, int p1) {
        return null;
    }
    public static java.util.List getMaliciousAppInfos(int p0) {
        return new java.util.ArrayList<>();
    }
    public static void registMaliAppInfoListener(com.hihonor.android.hnsystemmanager.HsmSecurityProxy.MaliAppInfoListener p0, int p1) {
    }
    public static void unregistMaliAppInfoListener(com.hihonor.android.hnsystemmanager.HsmSecurityProxy.MaliAppInfoListener p0) {
    }
    public interface MaliAppInfoListener {
    }
    public static class MaliciousAppInfo {
        public java.lang.String[] forbiddenPermissions;
        public boolean isMalicious;
        public boolean isRestricted;
        public java.lang.String packageName;
    }
}
