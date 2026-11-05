package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class ServiceManager {
    public static android.os.IBinder checkService(java.lang.String p0) {
        try { return (android.os.IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, p0); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    public static android.os.IBinder getService(java.lang.String p0) {
        try { return (android.os.IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, p0); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
}
