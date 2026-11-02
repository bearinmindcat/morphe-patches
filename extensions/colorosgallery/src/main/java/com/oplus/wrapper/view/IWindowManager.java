package com.oplus.wrapper.view;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public interface IWindowManager {
    int getInitialDisplayDensity(int p0);
    boolean hasNavigationBar(int p0);
    public static abstract class Stub extends android.os.Binder implements com.oplus.wrapper.view.IWindowManager {
        public Stub() {
        }
        public static com.oplus.wrapper.view.IWindowManager asInterface(android.os.IBinder p0) {
            return org.ungoogled.colorosgallery.ColorOS.absent(com.oplus.wrapper.view.IWindowManager.class);
        }
    }
}
