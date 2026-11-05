package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OCDMessageQueue {
    public OCDMessageQueue() {
    }
    public static com.oplus.dispatch.OCDMessageQueue getCurrentThreadOCDMessageQueue() {
        return org.ungoogled.coloroslauncher.Dispatch.queue(android.os.Looper.myLooper());
    }
    public void quit(boolean p0) {
        org.ungoogled.coloroslauncher.Dispatch.quit(this, p0);
    }
}
