package com.oplus.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Clock uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusUsbEnvironment {
    public static java.lang.String getExternalPath(android.content.Context p0) {
        return null;
    }
    public static java.io.File getExternalSdDirectory(android.content.Context p0) {
        return null;
    }
    public static java.lang.String getExternalSdState(android.content.Context p0) {
        return android.os.Environment.MEDIA_REMOVED;
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
    public static java.lang.String getMultiappSdDirectory() {
        return "";
    }
    public static java.util.List getOtgPath(android.content.Context p0) {
        return new java.util.ArrayList<>();
    }
    public static int getPathType(android.content.Context p0, java.lang.String p1) {
        return 0;
    }
    public static boolean isExternalSDRemoved(android.content.Context p0) {
        return true;
    }
    public static boolean isNestMounted() {
        return false;
    }
    public static boolean isVolumeMounted(android.content.Context p0, java.lang.String p1) {
        return false;
    }
}
