package com.oplus.wrapper.view;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class Choreographer {
    public static android.view.Choreographer getSfInstance() {
        return android.os.Looper.myLooper() == null ? null : android.view.Choreographer.getInstance();
    }
}
