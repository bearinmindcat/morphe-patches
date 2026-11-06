package miui.app;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures HyperOS launcher uses, answering "absent" off HyperOS (no Xiaomi code). */
public interface IMiuiFreeFormGuideTipServices {
    int getShowGuideClick();
    int getShowGuideController();
    int getShowGuideDock();
    int getShowGuideFreeform();
    int getShowGuideNotification();
    int getShowGuidePin();
    int getShowGuideSideBar();
    int getShowGuideSplit();
    boolean hasTipView();
    boolean hasTipViewType(int p0);
    void removeFreeFormTipView(int p0);
    void setShowGuideClick(int p0);
    void setShowGuideController(int p0);
    void setShowGuideFreeform(int p0);
    void setShowGuideNotification(int p0);
    void setShowGuidePin(int p0);
    void setShowGuideSideBar(int p0);
    void setShowGuideSplit(int p0);
    void showFreeFormGuideDialog(int p0);
    void showFreeFormTipView(int p0, int p1, int p2, int p3);
    void showFreeFormTipViewByClassName(int p0, int p1, int p2, int p3, java.lang.String p4);
    public static abstract class Stub extends android.os.Binder implements miui.app.IMiuiFreeFormGuideTipServices {
        public Stub() {
        }
        public static miui.app.IMiuiFreeFormGuideTipServices asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
