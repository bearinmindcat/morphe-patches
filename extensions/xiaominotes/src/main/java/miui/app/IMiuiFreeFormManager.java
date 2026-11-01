package miui.app;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Notes uses, answering "absent" off HyperOS (no Xiaomi code). */
public interface IMiuiFreeFormManager {
    java.util.List getAllFreeFormStackInfosOnDisplay(int p0);
    java.util.List getAllPinedFreeFormStackInfosOnDisplay(int p0);
    int getMaxMiuiFreeFormStackCountForFlashBack(java.lang.String p0, boolean p1);
    void hidePinFloatingWindow(int p0);
    boolean isSupportPin();
    boolean unPinFloatingWindow(android.graphics.Rect p0, int p1, float p2, float p3, boolean p4);
    void updatePinFloatingWindowPos(android.graphics.Rect p0, int p1);
    public static abstract class Stub extends android.os.Binder implements miui.app.IMiuiFreeFormManager {
        public Stub() {
        }
        public static miui.app.IMiuiFreeFormManager asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
