package com.oplus.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Screen Recording uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusUsbEnvironment {
    public static java.io.File getExternalSdDirectory(android.content.Context p0) {
        return null;
    }
    public static java.lang.String getExternalSdState(android.content.Context p0) {
        return android.os.Environment.MEDIA_REMOVED;
    }
    public static java.io.File getInternalSdDirectory(android.content.Context p0) {
        return android.os.Environment.getExternalStorageDirectory();
    }
}
