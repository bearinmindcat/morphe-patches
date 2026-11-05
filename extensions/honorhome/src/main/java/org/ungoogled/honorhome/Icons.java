package org.ungoogled.honorhome;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * App icons. Honor Home draws an adaptive icon's two layers into a bitmap whole and leaves the shape to MagicOS's theme
 * engine, which crops and masks it; elsewhere the icons came out as full squares of the layers' bleed area. Here the
 * layers are drawn as an adaptive icon is meant to be shown (the middle two thirds, Android's own crop) inside Honor's
 * rounded-square icon shape.
 */
public final class Icons {
    /** Corner radius of Honor's icon shape, as a share of the icon's size. */
    private static final float CORNER = 0.25f;
    /** An adaptive icon's layers are 1.5 times the shown icon (AdaptiveIconDrawable's 25% inset on each side). */
    private static final float LAYER_SCALE = 1.5f;

    private Icons() {
    }

    /** Replaces LayerDrawable.draw(canvas) in Honor Home's adaptive icon conversion. */
    public static void drawAdaptive(Drawable layers, Canvas canvas) {
        Rect bounds = layers.getBounds();
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) {
            layers.draw(canvas);
            return;
        }
        int save = canvas.save();
        Path shape = new Path();
        float r = Math.min(w, h) * CORNER;
        shape.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, r, r, Path.Direction.CW);
        canvas.clipPath(shape);
        canvas.scale(LAYER_SCALE, LAYER_SCALE, bounds.exactCenterX(), bounds.exactCenterY());
        layers.draw(canvas);
        canvas.restoreToCount(save);
    }
}
