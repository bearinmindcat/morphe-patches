package com.samsung.android.view;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures One UI Home uses, answering "absent" off One UI (no Samsung code). */
public class SemWindowManager {
    public SemWindowManager() {
    }
    public static com.samsung.android.view.SemWindowManager getInstance() {
        return new com.samsung.android.view.SemWindowManager();
    }
    public int getUserDensity() {
        return 0;
    }
    public boolean isFolded() {
        return false;
    }
    public static boolean isSupportAspectRatioMode(android.content.Context p0) {
        return false;
    }
    public void registerFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0, android.os.Handler p1) {
    }
    public void registerSystemKeyEvent(int p0, android.content.ComponentName p1, int p2) {
    }
    public android.graphics.Bitmap screenshot(int p0, int p1, boolean p2, android.graphics.Rect p3, int p4, int p5, boolean p6) {
        return null;
    }
    public void unregisterFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0) {
    }
    public void unregisterSystemKeyEvent(int p0, android.content.ComponentName p1) {
    }
    public interface FoldStateListener {
    }
}
