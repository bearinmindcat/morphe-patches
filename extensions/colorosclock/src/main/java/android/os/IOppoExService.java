package android.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Clock uses, answering "absent" off ColorOS (no OPPO code). */
public interface IOppoExService {
    boolean registerInputEvent(android.os.IOppoExInputCallBack p0);
    boolean registerRawInputEvent(android.os.IOppoExInputCallBack p0);
    void unregisterInputEvent(android.os.IOppoExInputCallBack p0);
    public static abstract class Stub extends android.os.Binder implements android.os.IOppoExService {
        public Stub() {
        }
        public static android.os.IOppoExService asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
