package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OCDExecutorService extends java.util.concurrent.ThreadPoolExecutor {
    public OCDExecutorService(java.lang.String p0, com.oplus.dispatch.QueueAttr p1, com.oplus.dispatch.DispatchQueue p2) {
        super(p1 == com.oplus.dispatch.QueueAttr.QUEUE_CONCURRENT ? Math.max(2, Runtime.getRuntime().availableProcessors()) : 1, p1 == com.oplus.dispatch.QueueAttr.QUEUE_CONCURRENT ? Math.max(2, Runtime.getRuntime().availableProcessors()) : 1, 10L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), org.ungoogled.coloroslauncher.Dispatch.threads(p0)); allowCoreThreadTimeOut(true);
    }
}
