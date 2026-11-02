package com.oppo.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus My Files uses, answering "absent" off ColorOS (no OPPO code). */
public class OppoUsbEnvironment {
    public static java.lang.String getExternalPath(android.content.Context p0) {
        return null;
    }
    public static java.lang.String getInternalPath(android.content.Context p0) {
        return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
    }
    public static java.io.File getInternalSdDirectory(android.content.Context p0) {
        return android.os.Environment.getExternalStorageDirectory();
    }
    public static java.lang.String getMultiappSdDirectory() {
        return "";
    }
    public static java.util.List getOtgPath(android.content.Context p0) {
        return new java.util.ArrayList<>();
    }
}
