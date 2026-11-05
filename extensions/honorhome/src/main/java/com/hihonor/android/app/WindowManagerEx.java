package com.hihonor.android.app;

/** Stand-in (written by hand from the generated one): MagicOS window manager calls; a display's own size from Android. */
public class WindowManagerEx {
    /** A display's physical (initial) size, in its natural orientation like Android's own initial display size. */
    public static void getDisplaySize(int p0, int p1, android.graphics.Point p2) {
        android.content.Context app = org.ungoogled.honorhome.App.context();
        if (app == null || p2 == null) return;
        android.view.Display d = app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(p1);
        if (d == null) d = app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        android.view.Display.Mode mode = d.getMode();
        p2.set(mode.getPhysicalWidth(), mode.getPhysicalHeight());
    }
    public static boolean isFixedRotating() {
        return false;
    }
    public static void registerDockedStackListener(com.hihonor.android.view.IDockedStackListenerEx p0) {
    }
    public static void setAboveAppWindowsContainersVisible(boolean p0) {
    }
    public static void setAppWindowExitInfo(android.os.Bundle p0, android.graphics.Bitmap p1) {
    }
    public static void setGestureNavMode(android.content.Context p0, int p1, int p2, int p3) {
    }
    public static void setWallpaperScaleForLauncherUnlockAnim(boolean p0) {
    }
}
