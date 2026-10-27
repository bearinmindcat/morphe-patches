package com.samsung.android.camera.iris;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Notes uses, answering "absent" off One UI (no Samsung code). */
public interface IIrisService {
    void authenticate(android.os.IBinder p0, android.os.IBinder p1, int p2, int p3, int p4, int p5, long p6, int p7, com.samsung.android.camera.iris.IIrisServiceReceiver p8, int p9, java.lang.String p10, android.os.Bundle p11, byte[] p12);
    void cancelAuthentication(android.os.IBinder p0, java.lang.String p1);
    void enableIRImageCallback(int p0, java.lang.String p1, int p2);
    java.util.List getEnrolledIrises(int p0, java.lang.String p1);
    boolean hasDisabledIrises(int p0, java.lang.String p1);
    boolean hasEnrolledIrises(int p0, java.lang.String p1);
    boolean isHardwareDetected(long p0, java.lang.String p1);
    int request(android.os.IBinder p0, int p1, byte[] p2, byte[] p3, int p4, int p5, com.samsung.android.camera.iris.IIrisServiceReceiver p6);
    void setIrisViewType(int p0, java.lang.String p1, int p2);
    public static abstract class Stub extends android.os.Binder implements com.samsung.android.camera.iris.IIrisService {
        public Stub() {
        }
        public static com.samsung.android.camera.iris.IIrisService asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
