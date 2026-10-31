package com.samsung.android.media;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Photo Editor uses, answering "absent" off One UI (no Samsung code). */
public class SemExtendedFormat {
    public static long addData(android.os.ParcelFileDescriptor p0, java.lang.String p1, byte[] p2, int p3, int p4) {
        return 0L;
    }
    public static int addData(java.io.File p0, java.lang.String p1, java.io.File p2, int p3, int p4) {
        return 0;
    }
    public static int addData(java.io.File p0, java.lang.String p1, byte[] p2, int p3, int p4) {
        return 0;
    }
    public static int copyAllData(java.io.File p0, java.io.File p1) {
        return 0;
    }
    public static boolean deleteAllData(java.io.File p0) {
        return false;
    }
    public static boolean deleteData(java.io.File p0, java.lang.String p1) {
        return false;
    }
    public static byte[] getData(java.io.File p0, java.lang.String p1) {
        return new byte[0];
    }
    public static int getDataCount(java.io.File p0) {
        return 0;
    }
    public static com.samsung.android.media.SemExtendedFormat.DataPosition getDataPosition(java.io.File p0, java.lang.String p1) {
        return null;
    }
    public static int getDataType(java.io.File p0, java.lang.String p1) {
        return 0;
    }
    public static int[] getDataTypeArray(java.io.File p0) {
        return new int[0];
    }
    public static java.lang.String[] getKeyNameArray(java.io.File p0) {
        return new java.lang.String[0];
    }
    public static boolean hasData(java.io.File p0, int p1) {
        return false;
    }
    public static boolean hasData(java.io.File p0, java.lang.String p1) {
        return false;
    }
    public static boolean isValidFile(java.io.File p0) {
        return false;
    }
    public static class DataPosition {
        public long length;
        public long offset;
    }
}
