package com.hihonor.android.view;

/**
 * Stand-in (written by hand): MagicOS's copy of a display's (hidden) DisplayInfo, filled from Android's public Display
 * calls. Honor Home sizes its whole layout from it.
 */
public class DisplayInfoEx {
    private int width;
    private int height;
    private int rotation;

    public DisplayInfoEx() {
    }
    public static boolean getDisplayInfo(android.view.Display p0, com.hihonor.android.view.DisplayInfoEx p1) {
        if (p0 == null || p1 == null) return false;
        android.graphics.Point size = new android.graphics.Point();
        p0.getRealSize(size);
        p1.width = size.x;
        p1.height = size.y;
        p1.rotation = p0.getRotation();
        return true;
    }
    public int getLogicalHeight() {
        return height;
    }
    public int getLogicalWidth() {
        return width;
    }
    public int getRotation() {
        return rotation;
    }
}
