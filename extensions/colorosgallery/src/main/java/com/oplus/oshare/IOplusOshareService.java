package com.oplus.oshare;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public interface IOplusOshareService {
    void cancelTask(com.oplus.oshare.OplusOshareDevice p0);
    boolean isSendOn();
    void pause();
    void registerCallback(com.oplus.oshare.IOplusOshareCallback p0);
    void resume();
    void scan();
    void sendData(android.content.Intent p0, com.oplus.oshare.OplusOshareDevice p1);
    void stop();
    void switchSend(boolean p0);
    void unregisterCallback(com.oplus.oshare.IOplusOshareCallback p0);
    public static abstract class Stub extends android.os.Binder implements com.oplus.oshare.IOplusOshareService {
        public Stub() {
        }
        public static com.oplus.oshare.IOplusOshareService asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
