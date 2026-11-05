package com.oplus.wrapper.app;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public interface IActivityManager {
    android.content.res.Configuration getConfiguration();
    boolean updateConfiguration(android.content.res.Configuration p0);
    public static abstract class Stub extends android.os.Binder implements com.oplus.wrapper.app.IActivityManager {
        public Stub() {
        }
        public static com.oplus.wrapper.app.IActivityManager asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
