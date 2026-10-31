package com.samsung.android.view;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Clock uses, answering "absent" off One UI (no Samsung code). */
public class SemWindowManager {
    public SemWindowManager() {
    }
    public static com.samsung.android.view.SemWindowManager getInstance() {
        return new com.samsung.android.view.SemWindowManager();
    }
    public boolean isFolded() {
        return false;
    }
    public void registerFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0, android.os.Handler p1) {
    }
    public boolean requestSystemKeyEvent(int p0, android.content.ComponentName p1, boolean p2) {
        return false;
    }
    public void unregisterFoldStateListener(com.samsung.android.view.SemWindowManager.FoldStateListener p0) {
    }
    public interface FoldStateListener {
    }
}
