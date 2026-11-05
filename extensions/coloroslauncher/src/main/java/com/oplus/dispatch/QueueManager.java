package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class QueueManager {
    public static com.oplus.dispatch.OCDMessageQueue messageQueueCreate(java.lang.String p0, com.oplus.dispatch.QueueAttr p1, com.oplus.dispatch.DispatchQueue p2) {
        return org.ungoogled.coloroslauncher.Dispatch.queue(org.ungoogled.coloroslauncher.Dispatch.looper(p0));
    }
    public static com.oplus.dispatch.SourceQueue queueCreate(java.lang.String p0, com.oplus.dispatch.QueueAttr p1) {
        return new com.oplus.dispatch.SourceQueue();
    }
    public static boolean updateGlobalQueues(int p0, int p1, int p2, boolean p3) {
        return false;
    }
}
