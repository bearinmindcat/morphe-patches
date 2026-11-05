package com.oplus.wrapper.app;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class ActivityThread {
    public static android.app.Application currentApplication() {
        try { return (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
}
