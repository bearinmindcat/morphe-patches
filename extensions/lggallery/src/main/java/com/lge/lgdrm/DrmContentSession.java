package com.lge.lgdrm;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses, answering "absent" off LG's ROM (no LG code). */
public class DrmContentSession {
    public DrmContentSession() {
    }
    public int consumeRight(long p0, long p1) {
        return 0;
    }
    public void destroySession() {
    }
    public long getDrmTime() {
        return 0L;
    }
    public com.lge.lgdrm.DrmContent getSelectedContent(boolean p0) {
        return null;
    }
    public com.lge.lgdrm.DrmRight getSelectedRight(boolean p0) {
        return null;
    }
    public boolean isActionSupported(int p0) {
        return false;
    }
    public int judgeRight(int p0, boolean p1) {
        return 0;
    }
    public int obtainNewRight(int p0, android.content.ComponentName p1) {
        return 0;
    }
    public com.lge.lgdrm.DrmStream openDrmStream() {
        return null;
    }
    public int setDecryptionInfo(boolean p0) {
        return 0;
    }
    public int syncDrmTime(android.content.ComponentName p0) {
        return 0;
    }
}
