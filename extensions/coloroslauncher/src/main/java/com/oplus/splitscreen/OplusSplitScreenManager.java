package com.oplus.splitscreen;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusSplitScreenManager {
    public OplusSplitScreenManager() {
    }
    public boolean dismissSplitScreenMode(int p0) {
        return false;
    }
    public static com.oplus.splitscreen.OplusSplitScreenManager getInstance() {
        return new com.oplus.splitscreen.OplusSplitScreenManager();
    }
    public int getSplitScreenState(android.content.Intent p0) {
        return 0;
    }
    public int getTopAppSplitScreenState() {
        return 0;
    }
    public boolean isInSplitScreenMode() {
        return false;
    }
    public void notifySplitScreenStateChanged(java.lang.String p0, android.os.Bundle p1, boolean p2) {
    }
    public boolean registerSplitScreenObserver(com.oplus.app.IOplusSplitScreenObserver p0) {
        return false;
    }
    public boolean splitScreenForRecentTasks(int p0) {
        return false;
    }
    public boolean unregisterSplitScreenObserver(com.oplus.app.IOplusSplitScreenObserver p0) {
        return false;
    }
}
