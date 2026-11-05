package com.oplus.flexiblewindow;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class FlexibleWindowManager {
    public FlexibleWindowManager() {
    }
    public android.os.Bundle calculateFlexibleWindowBounds(android.content.Intent p0, int p1, int p2) {
        return new android.os.Bundle();
    }
    public int getFlexibleWindowState(android.app.Activity p0) {
        return 0;
    }
    public int getFlexibleWindowState(android.app.TaskInfo p0) {
        return 0;
    }
    public static com.oplus.flexiblewindow.FlexibleWindowManager getInstance() {
        return new com.oplus.flexiblewindow.FlexibleWindowManager();
    }
    public android.os.Bundle getMinimizedInfo(android.content.Context p0) {
        return new android.os.Bundle();
    }
    public java.util.Map getPocketStudioTaskRegion(int p0) {
        return new java.util.HashMap<>();
    }
    public java.util.List getRecentEmbeddedTasksForContainer(int p0) {
        return new java.util.ArrayList<>();
    }
    public boolean isInPocketStudio(int p0) {
        return false;
    }
    public boolean isMinimizedPocketStudio(int p0) {
        return false;
    }
    public boolean isPocketStudioMultiWindowSupported(android.app.TaskInfo p0) {
        return false;
    }
    public boolean isSupportPocketStudio(android.content.Context p0) {
        return false;
    }
    public boolean notifyMinimizedReady(int p0, android.os.Bundle p1, com.oplus.flexiblewindow.FlexibleWindowManager.IMinimizedAnimationCallback p2, com.oplus.wrapper.view.IRecentsAnimationController p3) {
        return false;
    }
    public void removeEmbeddedContainerTask(int p0, int p1) {
    }
    public android.os.Bundle setExtraBundle(android.app.ActivityOptions p0, android.os.Bundle p1) {
        return p0 != null ? p0.toBundle() : new android.os.Bundle();
    }
    public void startDragFullscreenAnimation(android.os.Bundle p0, com.oplus.flexiblewindow.FlexibleWindowManager.IFullscreenDragAnimationCallback p1) {
    }
    public boolean startFlexibleWindowForRecentTasks(int p0, android.graphics.Rect p1) {
        return false;
    }
    public void startPocketStudio(android.content.Context p0, java.util.List p1, int[] p2, int[] p3, int p4, android.os.Bundle p5) {
    }
    public interface IFullscreenDragAnimationCallback {
    }
    public interface IMinimizedAnimationCallback {
    }
}
