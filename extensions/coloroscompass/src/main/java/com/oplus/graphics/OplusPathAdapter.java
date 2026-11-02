package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Compass uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusPathAdapter {
    public OplusPathAdapter(android.graphics.Path p0, int p1) {
        org.ungoogled.coloroscompass.ColorOS.wrap(this, p0);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float p1, float p2, android.graphics.Path.Direction p3) {
        android.graphics.Path p = org.ungoogled.coloroscompass.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2, p3);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float[] p1, android.graphics.Path.Direction p2) {
        android.graphics.Path p = org.ungoogled.coloroscompass.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2);
    }
}
