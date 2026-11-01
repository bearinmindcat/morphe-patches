package com.oplus.graphics;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Calculator uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusCanvas {
    public OplusCanvas(android.graphics.Canvas p0) {
        org.ungoogled.coloroscalculator.ColorOS.wrap(this, p0);
    }
    public void drawSmoothRoundRect(float p0, float p1, float p2, float p3, float p4, float p5, android.graphics.Paint p6, float p7) {
        android.graphics.Canvas c = org.ungoogled.coloroscalculator.ColorOS.wrapped(this, android.graphics.Canvas.class); if (c != null) c.drawRoundRect(p0, p1, p2, p3, p4, p5, p6);
    }
    public void drawSmoothRoundRect(android.graphics.RectF p0, float p1, float p2, android.graphics.Paint p3, float p4) {
        android.graphics.Canvas c = org.ungoogled.coloroscalculator.ColorOS.wrapped(this, android.graphics.Canvas.class); if (c != null) c.drawRoundRect(p0, p1, p2, p3);
    }
}
