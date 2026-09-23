package org.ungoogled.ui;

import android.graphics.RectF;
import android.graphics.drawable.shapes.RoundRectShape;

/**
 * Drop-in for android.graphics.drawable.shapes.RoundRectShape: every
 * `new RoundRectShape(...)` in Maps' dex (and its one subclass) is rewritten
 * to this by rect-shapes-shim-patch.sh. Same constructor; the radii arrays
 * are clamped when Rectangle Shapes is on.
 */
public class URoundRectShape extends RoundRectShape {
    public URoundRectShape(float[] outerRadii, RectF inset, float[] innerRadii) {
        super(Shapes.clamp(outerRadii), inset, Shapes.clamp(innerRadii));
    }
}
