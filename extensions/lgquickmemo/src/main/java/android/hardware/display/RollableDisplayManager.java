package android.hardware.display;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off LG's ROM (no LG code). */
public class RollableDisplayManager {
    public RollableDisplayManager() {
    }
    public static int DISPLAYPANEL_POSITION_16_9;
    public static int DISPLAYPANEL_POSITION_COLLAPSE;
    public static int DISPLAYPANEL_POSITION_EXPAND;
    public android.hardware.display.RDInfo getCurrentRDInfo() {
        return null;
    }
    public static android.hardware.display.RollableDisplayManager getInstance(android.content.Context p0) {
        return new android.hardware.display.RollableDisplayManager();
    }
    public static boolean isRollablePanel() {
        return false;
    }
    public void registerRDInfoListener(android.hardware.display.RollableDisplayManager.RDInfoListener p0) {
    }
    public void unregisterRDInfoListener(android.hardware.display.RollableDisplayManager.RDInfoListener p0) {
    }
    public static class RDInfoListener {
        public RDInfoListener() {
        }
        public void onInfoChanged(android.hardware.display.RDInfo p0) {
        }
    }
}
