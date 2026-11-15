package org.ungoogled.coloroslauncher;

import android.view.View;
import android.view.ViewParent;

/** Redrawing helpers. */
public final class Views {
    private Views() {
    }

    /**
     * Marks a view and every parent up to the window for redrawing. Off ColorOS an icon's own invalidate() during edit
     * mode did not reach the screen (its selection check stayed grey until the page redrew for another reason).
     */
    public static void invalidateUp(Object view) {
        if (!(view instanceof View)) return;
        View v = (View) view;
        v.invalidate();
        ViewParent p = v.getParent();
        while (p instanceof View) {
            ((View) p).invalidate();
            p = p.getParent();
        }
    }
}
