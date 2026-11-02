package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Screen Recording uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusOutlineAdapter {
    public OplusOutlineAdapter(android.graphics.Outline p0, int p1) {
        org.ungoogled.colorosscreenrecorder.ColorOS.wrap(this, p0);
    }
    public void setSmoothRoundRect(int p0, int p1, int p2, int p3, float p4) {
        android.graphics.Outline o = org.ungoogled.colorosscreenrecorder.ColorOS.wrapped(this, android.graphics.Outline.class); if (o != null) o.setRoundRect(p0, p1, p2, p3, p4);
    }
    public void setSmoothRoundRect(android.graphics.Rect p0, float p1) {
        android.graphics.Outline o = org.ungoogled.colorosscreenrecorder.ColorOS.wrapped(this, android.graphics.Outline.class); if (o != null) o.setRoundRect(p0, p1);
    }
}
