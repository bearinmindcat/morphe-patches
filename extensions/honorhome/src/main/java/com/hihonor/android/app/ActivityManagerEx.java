package com.hihonor.android.app;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Home uses, answering "absent" off MagicOS (no Honor code). */
public class ActivityManagerEx {
    public static void forceStopPackage(java.lang.String p0, int p1) {
    }
    public static int getActivityWindowMode(android.app.Activity p0) {
        return 0;
    }
    public static android.app.ActivityManager.RunningAppProcessInfo getAppRunningProcessByPid(int p0) {
        return null;
    }
    public static android.content.res.Configuration getConfiguration() {
        return new android.content.res.Configuration(android.content.res.Resources.getSystem().getConfiguration());
    }
    public static int getCurrentUser() {
        return android.os.Process.myUserHandle().hashCode();
    }
    public static int getFreeFormOrientation(int p0) {
        return 0;
    }
    public static android.os.Bundle getHwMultiWindowAppControlLists() {
        return null;
    }
    public static long getLastActiveTime(android.app.ActivityManager.RecentTaskInfo p0) {
        return 0L;
    }
    public static android.content.pm.ActivityInfo getLastResumedActivity() {
        return null;
    }
    public static long[] getProcessPss(int[] p0) {
        return new long[0];
    }
    public static java.util.List getRecentTasksForUser(android.app.ActivityManager p0, int p1, int p2, int p3) {
        return new java.util.ArrayList<>();
    }
    public static android.os.Bundle getTopActivity() {
        return null;
    }
    public static java.util.List getVisibleTasks() {
        return new java.util.ArrayList<>();
    }
    public static boolean isGameDndOn() {
        return false;
    }
    public static boolean preloadApplicationForLauncher(java.lang.String p0, int p1, int p2) {
        return false;
    }
    public static void reclaimOnDownEventForLauncher(java.lang.String p0, int p1, int p2) {
    }
    public static void registerHwActivityNotifier(com.hihonor.android.app.IHwActivityNotifierEx p0, java.lang.String p1) {
    }
    public static void registerUserSwitchObserver(com.hihonor.android.app.UserSwitchObserverEx p0, java.lang.String p1) {
    }
    public static void removeTasks(int[] p0) {
    }
    public static void resumeAppSwitches() {
    }
    public static int[] setFreeformStackVisibility(int p0, int[] p1, boolean p2) {
        return new int[0];
    }
    public static void setLaunchWindowingMode(android.app.ActivityOptions p0, int p1, android.content.Context p2) {
    }
    public static void setMultiDisplayParamsWithType(int p0, android.os.Bundle p1) {
    }
    public static void setSplitBarVisibility(boolean p0) {
    }
    public static int[] startActivitiesFromRecents(int[] p0, java.util.List p1, boolean p2, int p3) {
        return new int[0];
    }
    public static boolean startProfile(android.app.ActivityManager p0, android.os.UserHandle p1) {
        return false;
    }
    public static void unregisterHwActivityNotifier(com.hihonor.android.app.IHwActivityNotifierEx p0) {
    }
    public static void updatePersistentConfiguration(android.content.res.Configuration p0) {
    }
}
