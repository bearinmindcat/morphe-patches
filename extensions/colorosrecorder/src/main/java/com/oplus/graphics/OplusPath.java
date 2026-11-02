package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Recorder uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusPath {
    public OplusPath(android.graphics.Path p0) {
        org.ungoogled.colorosrecorder.ColorOS.wrap(this, p0);
    }
    public void addSmoothRoundRect(float p0, float p1, float p2, float p3, float p4, float p5, float p6, android.graphics.Path.Direction p7) {
        android.graphics.Path p = org.ungoogled.colorosrecorder.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2, p3, p4, p5, p7);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float p1, float p2, float p3, android.graphics.Path.Direction p4) {
        android.graphics.Path p = org.ungoogled.colorosrecorder.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2, p4);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float p1, float p2, android.graphics.Path.Direction p3) {
        android.graphics.Path p = org.ungoogled.colorosrecorder.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p1, p3);
    }
    public void addSmoothRoundRect(android.graphics.RectF p0, float[] p1, android.graphics.Path.Direction p2, float p3) {
        android.graphics.Path p = org.ungoogled.colorosrecorder.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) p.addRoundRect(p0, p1, p2);
    }
}
