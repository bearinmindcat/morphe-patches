package com.samsung.android.ims;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Contacts uses, answering "absent" off One UI (no Samsung code). */
public class SemImsManager {
    public SemImsManager(android.content.Context p0, com.samsung.android.ims.SemImsManager.ImsServiceConnectionListener p1, int p2) {
    }
    public void connectService() {
    }
    public android.content.ContentValues getConfigValues(java.lang.String[] p0) {
        return null;
    }
    public com.samsung.android.ims.SemImsRegistration getImsRegistration() {
        return null;
    }
    public java.lang.String getRcsProfileType() {
        return "";
    }
    public com.samsung.android.ims.SemImsRegistration[] getRegistrationInfo() {
        return new com.samsung.android.ims.SemImsRegistration[0];
    }
    public com.samsung.android.ims.SemImsRegistration getRegistrationInfoByServiceType(java.lang.String p0) {
        return null;
    }
    public boolean isForbidden() {
        return false;
    }
    public boolean isServiceAvailable(java.lang.String p0) {
        return false;
    }
    public void registerDmValueListener(com.samsung.android.ims.SemImsManager.DmConfigEventRelay p0) {
    }
    public boolean registerEpdgListener(com.samsung.android.ims.SemEpdgListener p0) {
        return false;
    }
    public void registerImsRegistrationListener(com.samsung.android.ims.SemImsRegistrationListener p0) {
    }
    public void registerSimMobilityStatusListener(com.samsung.android.ims.SemSimMobilityStatusListener p0) {
    }
    public interface DmConfigEventRelay {
    }
    public interface ImsServiceConnectionListener {
    }
}
