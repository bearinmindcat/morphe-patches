package com.samsung.android.desktopmode;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Calendar uses, answering "absent" off One UI (no Samsung code). */
public class SemDesktopModeManager {
    public com.samsung.android.desktopmode.SemDesktopModeState getDesktopModeState() {
        // Calendar reads the state without a check: answer "not in desktop mode" rather than null.
        return new com.samsung.android.desktopmode.SemDesktopModeState();
    }
    // 12.5: listens for desktop mode, which never starts off One UI.
    public void registerListener(com.samsung.android.desktopmode.SemDesktopModeManager.DesktopModeListener p0) {
    }
    public void unregisterListener(com.samsung.android.desktopmode.SemDesktopModeManager.DesktopModeListener p0) {
    }
    public interface DesktopModeListener {
        void onDesktopModeStateChanged(com.samsung.android.desktopmode.SemDesktopModeState p0);
    }
}
