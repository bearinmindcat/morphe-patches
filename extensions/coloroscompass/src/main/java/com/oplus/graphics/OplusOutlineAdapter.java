package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Compass uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusOutlineAdapter {
    public OplusOutlineAdapter(android.graphics.Outline p0, int p1) {
        org.ungoogled.coloroscompass.ColorOS.wrap(this, p0);
    }
    public void setSmoothRoundRect(android.graphics.Rect p0, float p1) {
        android.graphics.Outline o = org.ungoogled.coloroscompass.ColorOS.wrapped(this, android.graphics.Outline.class); if (o != null) o.setRoundRect(p0, p1);
    }
}
