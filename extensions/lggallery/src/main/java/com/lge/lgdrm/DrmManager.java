package com.lge.lgdrm;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses, answering "absent" off LG's ROM (no LG code). */
public class DrmManager {
    public static com.lge.lgdrm.DrmContentSession createContentSession(android.os.ParcelFileDescriptor p0, android.content.Context p1) {
        return new com.lge.lgdrm.DrmContentSession();
    }
    public static com.lge.lgdrm.DrmContentSession createContentSession(java.lang.String p0, android.content.Context p1) {
        return new com.lge.lgdrm.DrmContentSession();
    }
    public static int isDRM(android.os.ParcelFileDescriptor p0) {
        return 0;
    }
    public static int isDRM(java.lang.String p0) {
        return 0;
    }
}
