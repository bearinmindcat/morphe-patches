package org.ungoogled.nothinglauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Context.getDisplay(): the system launcher asks it of contexts that are not visual (its home screen preview), which
 * Android only answers for an activity or a window context; those get the default display here.
 */
public final class Windows {
    private Windows() {
    }

    /** The display a context belongs to; the default display for a context without one (the application's). */
    private static Display displayOf(Context c) {
        try {
            Display d = c.getDisplay();
            if (d != null) return d;
        } catch (UnsupportedOperationException e) {
            // Not a visual context.
        }
        return c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    public static Display getDisplay(Context c) {
        return displayOf(c);
    }

    public static Display getDisplay(android.content.ContextWrapper c) {
        return displayOf(c);
    }
}
