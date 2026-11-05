package android.os.oplusdevicepolicy;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusDevicepolicyManager {
    public OplusDevicepolicyManager() {
    }
    public boolean getBoolean(java.lang.String p0, int p1, boolean p2) {
        return p2;
    }
    public static android.os.oplusdevicepolicy.OplusDevicepolicyManager getInstance() {
        return new android.os.oplusdevicepolicy.OplusDevicepolicyManager();
    }
    public boolean registerOplusDevicepolicyObserver(java.lang.String p0, android.os.oplusdevicepolicy.OplusDevicepolicyManager.OplusDevicePolicyObserver p1) {
        return false;
    }
    public boolean unregisterOplusDevicePolicyObserver(android.os.oplusdevicepolicy.OplusDevicepolicyManager.OplusDevicePolicyObserver p0) {
        return false;
    }
    public interface OplusDevicePolicyObserver {
    }
}
