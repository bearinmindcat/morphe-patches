package com.oneplus.inner.app;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Launcher uses, answering "absent" off ColorOS (no OPPO code). */
public class ActivityManagerWrapper {
    public static boolean clearApplicationUserData(android.app.ActivityManager p0, java.lang.String p1) {
        return false;
    }
    public static void forceStopPackageAsUser(android.app.ActivityManager p0, java.lang.String p1, int p2) {
    }
    public static int getCurrentUser() {
        return 0;
    }
    public static java.util.List getFilteredTasks(int p0, boolean p1) {
        return new java.util.ArrayList<>();
    }
    public static java.util.List getTaskPkgList(int p0) {
        return new java.util.ArrayList<>();
    }
    public static void killUid(android.app.ActivityManager p0, int p1, java.lang.String p2) {
    }
    public static void registerProcessObserver(com.oneplus.inner.app.ActivityManagerWrapper.IProcessObserverWrapper p0) {
    }
    public static void registerTaskStackListener(com.oneplus.inner.app.ActivityManagerWrapper.ITaskStackListenerWrapper p0) {
    }
    public static void unregisterProcessObserver(com.oneplus.inner.app.ActivityManagerWrapper.IProcessObserverWrapper p0) {
    }
    public interface IProcessObserverWrapper {
    }
    public interface ITaskStackListenerWrapper {
    }
    public static class RecentTaskInfoWrapper {
        public static android.content.ComponentName getRealActivity(android.app.ActivityManager.RecentTaskInfo p0) {
            return null;
        }
    }
    public static class TaskSnapshotWrapper {
        public void destroy() {
        }
        public android.graphics.Bitmap getSnapshotBitmap() {
            return null;
        }
    }
}
