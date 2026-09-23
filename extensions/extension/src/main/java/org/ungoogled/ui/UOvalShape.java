package org.ungoogled.ui;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.shapes.OvalShape;

/**
 * Drop-in for android.graphics.drawable.shapes.OvalShape (circular icon
 * backgrounds, dots, avatars): every `new OvalShape()` in Maps' dex is
 * rewritten to this by the Rectangle shapes patch. Draws a small-radius
 * rectangle instead of an ellipse while Rectangle Shapes is on.
 */
public class UOvalShape extends OvalShape {
    public UOvalShape() { super(); }

    @Override public void draw(Canvas canvas, Paint paint) {
        if (Shapes.RECT) canvas.drawRoundRect(rect(), Shapes.r(), Shapes.r(), paint);
        else super.draw(canvas, paint);
    }

    @Override public void getOutline(Outline outline) {
        if (Shapes.RECT) {
            RectF r = rect();
            outline.setRoundRect((int) Math.ceil(r.left), (int) Math.ceil(r.top), (int) Math.floor(r.right), (int) Math.floor(r.bottom), Shapes.r());
        } else {
            super.getOutline(outline);
        }
    }
}
