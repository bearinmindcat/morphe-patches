package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Weather uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusPath {
    public OplusPath(android.graphics.Path p0) {
        org.ungoogled.colorosweather.ColorOS.wrap(this, p0);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float p1, float p2, android.graphics.Path.Direction p3) {
        android.graphics.Path p = org.ungoogled.colorosweather.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p1, p3);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float[] p1, android.graphics.Path.Direction p2, float p3) {
        android.graphics.Path p = org.ungoogled.colorosweather.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2);
    }
}
