package com.samsung.android.ims.options;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Contacts uses, answering "absent" off One UI (no Samsung code). */
public class SemCapabilityManager {
    public SemCapabilityManager(android.content.Context p0, com.samsung.android.ims.options.SemCapabilityManager.ConnectionListener p1, int p2) {
    }
    public com.samsung.android.ims.options.SemCapabilities[] getCapabilitiesByContactId(java.lang.String p0, int p1) {
        return new com.samsung.android.ims.options.SemCapabilities[0];
    }
    public com.samsung.android.ims.options.SemCapabilities getCapabilitiesByNumber(java.lang.String p0, int p1, boolean p2) {
        return null;
    }
    public com.samsung.android.ims.options.SemCapabilities getOwnCapabilities() {
        return null;
    }
    public void registerListener(com.samsung.android.ims.options.SemCapabilityListener p0) {
    }
    public void unregisterListener(com.samsung.android.ims.options.SemCapabilityListener p0) {
    }
    public interface ConnectionListener {
    }
}
