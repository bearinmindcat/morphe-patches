package android.os;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public interface IOplusExService {
    boolean registerInputEvent(android.os.IOplusExInputCallBack p0);
    void unregisterInputEvent(android.os.IOplusExInputCallBack p0);
    public static abstract class Stub extends android.os.Binder implements android.os.IOplusExService {
        public Stub() {
        }
        public static android.os.IOplusExService asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
