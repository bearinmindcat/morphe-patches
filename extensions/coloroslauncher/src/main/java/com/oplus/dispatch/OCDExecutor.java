package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OCDExecutor extends java.util.concurrent.ThreadPoolExecutor {
    public OCDExecutor(com.oplus.dispatch.SourceQueue p0) {
        super(1, 1, 10L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), org.ungoogled.coloroslauncher.Dispatch.threads("ocd-executor")); allowCoreThreadTimeOut(true);
    }
}
