package com.oplus.wrapper.util;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class SparseArray {
    public SparseArray(android.util.SparseArray p0) {
        org.ungoogled.coloroslauncher.ColorOS.wrap(this, p0);
    }
    public java.lang.Object removeReturnOld(int p0) {
        android.util.SparseArray a = org.ungoogled.coloroslauncher.ColorOS.wrapped(this, android.util.SparseArray.class); if (a == null) return null; Object old = a.get(p0); a.remove(p0); return old;
    }
}
