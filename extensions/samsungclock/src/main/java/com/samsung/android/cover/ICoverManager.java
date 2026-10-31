package com.samsung.android.cover;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Clock uses, answering "absent" off One UI (no Samsung code). */
public interface ICoverManager {
    void addLedNotification(android.os.Bundle p0);
    void disableCoverManager(boolean p0, android.os.IBinder p1, java.lang.String p2);
    boolean disableLcdOffByCover(android.os.IBinder p0, android.content.ComponentName p1);
    boolean enableLcdOffByCover(android.os.IBinder p0, android.content.ComponentName p1);
    com.samsung.android.cover.CoverState getCoverState();
    boolean isCoverManagerDisabled();
    void registerCallback(android.os.IBinder p0, android.content.ComponentName p1);
    void registerListenerCallback(android.os.IBinder p0, android.content.ComponentName p1, int p2);
    void registerNfcTouchListenerCallback(int p0, android.os.IBinder p1, android.content.ComponentName p2);
    void removeLedNotification(android.os.Bundle p0);
    void sendDataToCover(int p0, byte[] p1);
    void sendDataToNfcLedCover(int p0, byte[] p1);
    void sendSystemEvent(android.os.Bundle p0);
    boolean unregisterCallback(android.os.IBinder p0);
    boolean unregisterNfcTouchListenerCallback(android.os.IBinder p0);
    public static abstract class Stub extends android.os.Binder implements com.samsung.android.cover.ICoverManager {
        public Stub() {
        }
        public static com.samsung.android.cover.ICoverManager asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
