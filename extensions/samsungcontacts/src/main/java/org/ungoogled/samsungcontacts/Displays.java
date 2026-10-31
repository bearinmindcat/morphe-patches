package org.ungoogled.samsungcontacts;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Context.getDisplay() for Contacts, which asks it of its application and receiver contexts (to tell whether it runs
 * in desktop windowing): Android throws for a context without a display, so those get the default display.
 */
public final class Displays {
    private Displays() {
    }

    public static Display getDisplay(Context context) {
        try {
            return context.getDisplay();
        } catch (UnsupportedOperationException e) {
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            return displays.getDisplay(Display.DEFAULT_DISPLAY);
        }
    }
}
