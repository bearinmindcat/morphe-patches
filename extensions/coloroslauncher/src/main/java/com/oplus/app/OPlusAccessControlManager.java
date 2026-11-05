package com.oplus.app;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OPlusAccessControlManager {
    public OPlusAccessControlManager() {
    }
    public static int USER_CURRENT;
    public void addEncryptPass(java.lang.String p0, int p1, int p2) {
    }
    public java.util.Map getAccessControlAppsInfo(java.lang.String p0, int p1) {
        return new java.util.HashMap<>();
    }
    public boolean getAccessControlEnabled(java.lang.String p0, int p1) {
        return false;
    }
    public static com.oplus.app.OPlusAccessControlManager getInstance() {
        return new com.oplus.app.OPlusAccessControlManager();
    }
    public boolean isEncryptPass(java.lang.String p0, int p1) {
        return false;
    }
    public boolean isEncryptedPackage(java.lang.String p0, int p1) {
        return false;
    }
    public boolean registerAccessControlObserver(java.lang.String p0, com.oplus.app.IOplusAccessControlObserver p1) {
        return false;
    }
    public void setAccessControlAppsInfo(java.lang.String p0, java.util.Map p1, int p2) {
    }
    public void setAccessControlEnabled(java.lang.String p0, boolean p1, int p2) {
    }
    public boolean unregisterAccessControlObserver(java.lang.String p0, com.oplus.app.IOplusAccessControlObserver p1) {
        return false;
    }
}
