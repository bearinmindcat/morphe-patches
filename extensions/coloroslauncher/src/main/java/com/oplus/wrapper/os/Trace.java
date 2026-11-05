package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class Trace {
    public static long TRACE_TAG_VIEW = 8L;
    public static void asyncTraceBegin(long p0, java.lang.String p1, int p2) {
    }
    public static void asyncTraceEnd(long p0, java.lang.String p1, int p2) {
    }
    public static void traceBegin(long p0, java.lang.String p1) {
        android.os.Trace.beginSection(p1);
    }
    public static void traceEnd(long p0) {
        android.os.Trace.endSection();
    }
}
