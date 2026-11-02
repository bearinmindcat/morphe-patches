package android.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Clock uses, answering "absent" off ColorOS (no OPPO code). */
public interface IOplusExService {
    boolean registerInputEvent(android.os.IOplusExInputCallBack p0);
    boolean registerRawInputEvent(android.os.IOplusExInputCallBack p0);
    void unregisterInputEvent(android.os.IOplusExInputCallBack p0);
    public static abstract class Stub extends android.os.Binder implements android.os.IOplusExService {
        public Stub() {
        }
        public static android.os.IOplusExService asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
