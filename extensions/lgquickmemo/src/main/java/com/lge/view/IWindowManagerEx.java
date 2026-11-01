package com.lge.view;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off LG's ROM (no LG code). */
public interface IWindowManagerEx {
    java.util.List getWindowInfoList(int p0, boolean p1);
    void setVsyncPhaseOffset(long p0, long p1, long p2);
    public static abstract class Stub extends android.os.Binder implements com.lge.view.IWindowManagerEx {
        public Stub() {
        }
        public static com.lge.view.IWindowManagerEx asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
