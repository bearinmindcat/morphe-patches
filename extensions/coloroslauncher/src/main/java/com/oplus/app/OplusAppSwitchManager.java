package com.oplus.app;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusAppSwitchManager {
    public OplusAppSwitchManager() {
    }
    public static com.oplus.app.OplusAppSwitchManager getInstance() {
        return new com.oplus.app.OplusAppSwitchManager();
    }
    public boolean registerAppSwitchObserver(android.content.Context p0, com.oplus.app.OplusAppSwitchManager.OnAppSwitchObserver p1, com.oplus.app.OplusAppSwitchConfig p2) {
        return false;
    }
    public boolean unregisterAppSwitchObserver(android.content.Context p0, com.oplus.app.OplusAppSwitchManager.OnAppSwitchObserver p1) {
        return false;
    }
    public interface OnAppSwitchObserver {
        void onActivityEnter(com.oplus.app.OplusAppEnterInfo p0);
        void onActivityExit(com.oplus.app.OplusAppExitInfo p0);
        void onAppEnter(com.oplus.app.OplusAppEnterInfo p0);
        void onAppExit(com.oplus.app.OplusAppExitInfo p0);
    }
}
