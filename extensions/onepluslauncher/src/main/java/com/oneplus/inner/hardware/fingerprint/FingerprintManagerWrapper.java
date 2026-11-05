package com.oneplus.inner.hardware.fingerprint;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Launcher uses, answering "absent" off ColorOS (no OPPO code). */
public class FingerprintManagerWrapper {
    public static boolean hasEnrolledFingerprints(android.hardware.fingerprint.FingerprintManager p0, int p1) {
        return false;
    }
    public static void remove(android.hardware.fingerprint.FingerprintManager p0, com.oneplus.inner.hardware.fingerprint.FingerprintWrapper p1, int p2, com.oneplus.inner.hardware.fingerprint.FingerprintManagerWrapper.RemovalCallbackWrapper p3) {
    }
    public static void setActiveUser(android.hardware.fingerprint.FingerprintManager p0, int p1) {
    }
    public static class RemovalCallbackWrapper {
        public RemovalCallbackWrapper() {
        }
        public void onRemovalError(com.oneplus.inner.hardware.fingerprint.FingerprintWrapper p0, int p1, java.lang.CharSequence p2) {
        }
        public void onRemovalSucceeded(com.oneplus.inner.hardware.fingerprint.FingerprintWrapper p0, int p1) {
        }
    }
}
