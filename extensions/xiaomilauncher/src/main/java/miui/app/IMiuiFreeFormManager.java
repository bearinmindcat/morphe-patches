package miui.app;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures HyperOS launcher uses, answering "absent" off HyperOS (no Xiaomi code). */
public interface IMiuiFreeFormManager {
    java.util.List getAllFreeFormStackInfosOnDisplay(int p0);
    java.util.List getAllPinedFreeFormStackInfosOnDisplay(int p0);
    boolean hasMiuiFreeformOnShellFeature();
    boolean isSupportPin();
    void notifyCameraStateChanged(java.lang.String p0, int p1);
    boolean openCameraInFreeForm(java.lang.String p0);
    int startSmallFreeformFromNotification();
}
