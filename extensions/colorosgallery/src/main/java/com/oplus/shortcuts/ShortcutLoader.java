package com.oplus.shortcuts;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class ShortcutLoader {
    public ShortcutLoader(android.content.Context p0, com.oplus.wrapper.app.prediction.AppPredictor p1, android.os.UserHandle p2, android.content.IntentFilter p3, java.util.function.Consumer p4) {
    }
    public void queryShortcuts(com.oplus.shortcuts.chooser.DisplayResolveInfo[] p0) {
    }
    public static class Result {
        public com.oplus.shortcuts.chooser.DisplayResolveInfo[] appTargets;
        public java.util.Map directShareAppTargetCache;
        public java.util.Map directShareShortcutInfoCache;
        public boolean isFromAppPredictor;
        public com.oplus.shortcuts.ShortcutLoader.ShortcutResultInfo[] shortcutsByApp;
        public int hashCode() {
            return 0;
        }
    }
    public static class ShortcutResultInfo {
        public com.oplus.shortcuts.chooser.DisplayResolveInfo appTarget;
        public java.util.List shortcuts;
    }
}
