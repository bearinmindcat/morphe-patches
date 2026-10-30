package com.samsung.android.cover;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Music uses, answering "absent" off One UI (no Samsung code). */
public interface ICoverManager {
    com.samsung.android.cover.CoverState getCoverState();
    void registerCallback(android.os.IBinder p0, android.content.ComponentName p1);
    void registerListenerCallback(android.os.IBinder p0, android.content.ComponentName p1, int p2);
    void sendDataToCover(int p0, byte[] p1);
    boolean unregisterCallback(android.os.IBinder p0);
    public static abstract class Stub extends android.os.Binder implements com.samsung.android.cover.ICoverManager {
        public Stub() {
        }
        public static com.samsung.android.cover.ICoverManager asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
