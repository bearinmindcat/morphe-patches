package android.os.customize;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class OplusCustomizeRestrictionManager {
    public OplusCustomizeRestrictionManager() {
    }
    public java.util.List getAppUninstallationPackageList(int p0) {
        return new java.util.ArrayList<>();
    }
    public int getAppUninstallationPolicies() {
        return 0;
    }
    public java.util.List getApplicationDisabledInLauncherOrRecentTask(int p0) {
        return new java.util.ArrayList<>();
    }
    public static android.os.customize.OplusCustomizeRestrictionManager getInstance(android.content.Context p0) {
        return new android.os.customize.OplusCustomizeRestrictionManager();
    }
    public boolean getSplitScreenDisable(android.content.ComponentName p0) {
        return false;
    }
    public boolean isChangeWallpaperDisabled(android.content.ComponentName p0) {
        return false;
    }
    public boolean isNavigationBarDisabled() {
        return false;
    }
    public boolean isTaskButtonDisabled() {
        return false;
    }
}
