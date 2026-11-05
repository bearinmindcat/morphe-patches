package com.oplus.wrapper.content;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class Context {
    public Context(android.content.Context p0) {
        org.ungoogled.coloroslauncher.ColorOS.wrap(this, p0);
    }
    public android.os.Handler getMainThreadHandler() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }
}
