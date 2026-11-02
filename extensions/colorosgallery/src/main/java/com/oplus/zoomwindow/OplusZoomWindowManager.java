package com.oplus.zoomwindow;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusZoomWindowManager {
    public OplusZoomWindowManager() {
    }
    public com.oplus.zoomwindow.OplusZoomWindowInfo getCurrentZoomWindowState() {
        return new com.oplus.zoomwindow.OplusZoomWindowInfo();
    }
    public static com.oplus.zoomwindow.OplusZoomWindowManager getInstance() {
        return new com.oplus.zoomwindow.OplusZoomWindowManager();
    }
    public boolean isSupportZoomWindowMode() {
        return false;
    }
    public boolean registerZoomWindowObserver(com.oplus.zoomwindow.IOplusZoomWindowObserver p0) {
        return false;
    }
    public int startZoomWindow(android.content.Intent p0, android.os.Bundle p1, int p2, java.lang.String p3) {
        return 0;
    }
    public boolean unregisterZoomWindowObserver(com.oplus.zoomwindow.IOplusZoomWindowObserver p0) {
        return false;
    }
}
