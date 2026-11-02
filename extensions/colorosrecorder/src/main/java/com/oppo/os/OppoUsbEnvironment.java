package com.oppo.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Recorder uses, answering "absent" off ColorOS (no OPPO code). */
public class OppoUsbEnvironment {
    public static java.lang.String getExternalPath(android.content.Context p0) {
        return null;
    }
    public static java.io.File getExternalSdDirectory(android.content.Context p0) {
        return null;
    }
    public static java.lang.String getExternalSdState(android.content.Context p0) {
        return android.os.Environment.MEDIA_REMOVED;
    }
    public static java.io.File getExternalStorageDirectory() {
        return android.os.Environment.getExternalStorageDirectory();
    }
    public static java.lang.String getInternalPath(android.content.Context p0) {
        return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
    }
    public static java.io.File getInternalSdDirectory(android.content.Context p0) {
        return android.os.Environment.getExternalStorageDirectory();
    }
    public static java.lang.String getInternalSdState(android.content.Context p0) {
        return android.os.Environment.getExternalStorageState();
    }
    public static boolean isVolumeMounted(android.content.Context p0, java.lang.String p1) {
        return false;
    }
}
