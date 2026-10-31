package com.samsung.android.desktopmode;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Calendar uses, answering "absent" off One UI (no Samsung code). */
public class SemDesktopModeManager {
    public com.samsung.android.desktopmode.SemDesktopModeState getDesktopModeState() {
        // Calendar reads the state without a check: answer "not in desktop mode" rather than null.
        return new com.samsung.android.desktopmode.SemDesktopModeState();
    }
}
