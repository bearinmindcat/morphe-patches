package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OCDHandler extends android.os.Handler {
    public OCDHandler(android.os.Looper p0) {
        super(p0);
    }
    public OCDHandler(com.oplus.dispatch.OCDMessageQueue p0) {
        super(org.ungoogled.coloroslauncher.Dispatch.looperOf(p0));
    }
    public OCDHandler(java.lang.String p0) {
        super(org.ungoogled.coloroslauncher.Dispatch.looper(p0));
    }
    public OCDHandler(java.lang.String p0, android.os.Handler.Callback p1) {
        super(org.ungoogled.coloroslauncher.Dispatch.looper(p0), p1);
    }
    public com.oplus.dispatch.OCDMessageQueue getOcdMessageQueue() {
        return org.ungoogled.coloroslauncher.Dispatch.queue(getLooper());
    }
}
