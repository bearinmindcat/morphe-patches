package org.ungoogled.samsungreminder;

import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.SemBlurInfo;
import android.view.View;

/**
 * View.semSetBlurInfo off One UI: Reminder's panels (the quick add sheet first) have only an unfilled shape behind
 * them and rely on One UI blurring what is behind, so without it they are see-through. A plain background in the
 * theme's background colour, with the shape's (or the blur's) corners, stands in; it goes again with the blur.
 * Views with a real background of their own keep it.
 */
final class Blur {
    private Blur() {
    }

    private static final class Plain extends GradientDrawable {
    }

    /** The shape of a background that only shapes (no fill), or null if it draws something. */
    private static GradientDrawable shapeOnly(Drawable d) {
        if (d instanceof GradientDrawable) {
            ColorStateList c = ((GradientDrawable) d).getColor();
            return c == null || Color.alpha(c.getDefaultColor()) == 0 ? (GradientDrawable) d : null;
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) d;
            GradientDrawable found = null;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                GradientDrawable g = shapeOnly(layers.getDrawable(i));
                if (g == null) return null;
                found = g;
            }
            return found;
        }
        return null;
    }

    static void apply(View view, SemBlurInfo info) {
        if (view == null) return;
        Drawable background = view.getBackground();
        if (info == null) {
            if (background instanceof Plain) view.setBackground(null);
            return;
        }
        GradientDrawable shape = background instanceof Plain ? null : shapeOnly(background);
        if (background != null && !(background instanceof Plain) && shape == null) return;
        TypedArray a = view.getContext().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
        int color = a.getColor(0, 0xFFF7F7F7);
        a.recycle();
        Plain plain = new Plain();
        plain.setColor((color & 0x00FFFFFF) | 0xF2000000);
        float[] r = info.ungoogledCornerRadii;
        if (r != null) {
            plain.setCornerRadii(new float[]{r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3]});
        } else if (shape != null) {
            float[] radii = shape.getCornerRadii();
            if (radii != null) plain.setCornerRadii(radii); else plain.setCornerRadius(shape.getCornerRadius());
        }
        view.setBackground(plain);
    }
}
