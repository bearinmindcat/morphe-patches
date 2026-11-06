package com.samsung.android.multiwindow;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Home Up uses, answering "absent" off One UI (no Samsung code). */
public class MultiWindowManager {
    public MultiWindowManager() {
    }
    public static java.lang.String changeTransitModeToString(int p0) {
        return "";
    }
    public void clearAllDesktopRememberedBoundsRatio(int p0) {
    }
    public void dismissSplitTask(android.os.IBinder p0, boolean p1) {
    }
    public void finishNaturalSwitching() {
    }
    public static java.lang.String forceHidingTransitToString(int p0) {
        return "";
    }
    public static com.samsung.android.multiwindow.MultiWindowManager getInstance() {
        return new com.samsung.android.multiwindow.MultiWindowManager();
    }
    public java.util.List getMWDisableRequesters() {
        return new java.util.ArrayList<>();
    }
    public java.util.List getMinimizedFreeformTasksForCurrentUser() {
        return new java.util.ArrayList<>();
    }
    public int getMultiSplitFlags() {
        return 0;
    }
    public int getPackageParserDensityDpi() {
        return 0;
    }
    public int getSupportedMultiWindowModes(android.content.pm.ActivityInfo p0) {
        return 0;
    }
    public com.samsung.android.multiwindow.SurfaceFreezerSnapshot getSurfaceFreezerSnapshot(int p0) {
        return null;
    }
    public java.util.List getVisibleTasks() {
        return new java.util.ArrayList<>();
    }
    public boolean inDesktopWindowing() {
        return false;
    }
    public boolean isAllTasksResizable(int p0, int p1, int p2) {
        return false;
    }
    public boolean isCornerGestureRunning() {
        return false;
    }
    public boolean isSplitImmersiveModeEnabled() {
        return false;
    }
    public boolean isValidCornerGesture(android.view.MotionEvent p0) {
        return false;
    }
    public boolean minimizeAllTasks(int p0) {
        return false;
    }
    public boolean minimizeTaskById(int p0) {
        return false;
    }
    public boolean minimizeTaskToSpecificPosition(int p0, boolean p1, int p2, int p3) {
        return false;
    }
    public void notifyDragSplitAppIconHasDrawable(boolean p0) {
    }
    public void notifyDragTaskStarted() {
    }
    public void notifyFreeformMinimizeAnimationEnd(int p0, android.graphics.PointF p1) {
    }
    public boolean preventNaturalSwitching(int p0) {
        return false;
    }
    public void registerFreeformCallback(com.samsung.android.multiwindow.IFreeformCallback p0) {
    }
    public void registerRemoteAppTransitionListener(com.samsung.android.multiwindow.IRemoteAppTransitionListener p0) {
    }
    public void reportDesktopRememberedBoundsRatio(int p0, java.lang.String p1, android.graphics.RectF p2, android.graphics.RectF p3) {
    }
    public void reportFreeformContainerPoint(android.graphics.PointF p0) {
    }
    public void restoreDesktopRememberedBoundsRatio(int p0, java.util.Map p1, java.util.Map p2) {
    }
    public void saveFreeformBounds(int p0) {
    }
    public void setInDesktopWindowing(boolean p0) {
    }
    public boolean startNaturalSwitching(android.os.IBinder p0, android.os.IBinder p1) {
        return false;
    }
    public boolean supportMultiSplitAppMinimumSize() {
        return false;
    }
    public void unregisterFreeformCallback(com.samsung.android.multiwindow.IFreeformCallback p0) {
    }
}
