package com.samsung.android.view;

/**
 * Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Capture uses, answering "absent"
 * off One UI (no Samsung code). By hand: getInitialDisplaySize answers the display's natural size (the recorder sizes its
 * video from it; 0 x 0 divided by zero).
 */
public class SemWindowManager {
    public SemWindowManager() {
    }
    @SuppressWarnings("deprecation")
    public void getInitialDisplaySize(android.graphics.Point p0) {
        android.content.Context context = org.ungoogled.samsungcapture.Services.app();
        if (context != null) {
            android.view.Display display = context.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (display != null) {
                display.getRealSize(p0);
                int rotation = display.getRotation();
                if (rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270) {
                    p0.set(p0.y, p0.x);
                }
                if (p0.x > 0 && p0.y > 0) return;
            }
        }
        android.util.DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        p0.set(Math.min(metrics.widthPixels, metrics.heightPixels), Math.max(metrics.widthPixels, metrics.heightPixels));
    }
    public static com.samsung.android.view.SemWindowManager getInstance() {
        return new com.samsung.android.view.SemWindowManager();
    }
    public java.util.List getVisibleWindowInfoList() {
        return new java.util.ArrayList<>();
    }
    public boolean isFolded() {
        return false;
    }
    public void registerFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0, android.os.Handler p1) {
    }
    public android.graphics.Bitmap screenshot(int p0, int p1, boolean p2, android.graphics.Rect p3, int p4, int p5, boolean p6) {
        return null;
    }
    public void unregisterFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0) {
    }
    public interface FoldStateListener {
    }
    public static class VisibleWindowInfo {
        public boolean focused;
        public java.lang.String name;
        public java.lang.String packageName;
    }
}
