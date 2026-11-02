package com.oplus.app;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OPlusAccessControlManager {
    public OPlusAccessControlManager() {
    }
    public static int USER_CURRENT;
    public java.util.Map getAccessControlAppsInfo(java.lang.String p0, int p1) {
        return new java.util.HashMap<>();
    }
    public boolean getAccessControlEnabled(java.lang.String p0, int p1) {
        return false;
    }
    public boolean getApplicationAccessControlEnabledAsUser(java.lang.String p0, int p1) {
        return false;
    }
    public static com.oplus.app.OPlusAccessControlManager getInstance() {
        return new com.oplus.app.OPlusAccessControlManager();
    }
    public java.util.Map getPrivacyAppInfo(int p0) {
        return new java.util.HashMap<>();
    }
    public boolean registerAccessControlObserver(java.lang.String p0, com.oplus.app.IOplusAccessControlObserver p1) {
        return false;
    }
    public boolean unregisterAccessControlObserver(java.lang.String p0, com.oplus.app.IOplusAccessControlObserver p1) {
        return false;
    }
}
