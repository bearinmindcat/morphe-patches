package com.zte.performance.mindsync;

/** Generated stand-in (.github/scripts/redmagic-standins): names and signatures RedMagic Game Space uses, answering "absent" off RedMagic OS (no Nubia or ZTE code). */
public class MindSyncManager {
    public static class GamePerfSettingListItem {
        public boolean mIsScoped;
        public java.lang.String mKey;
        public java.lang.String mValue2;
        public java.lang.String mValue;
    }
    public static class Trigger {
        public static void acquirePerformanceLock(android.os.IBinder p0, java.lang.String p1, int p2, long p3) {
        }
        public static void acquirePerformanceLockDepend(android.os.IBinder p0, java.lang.String p1, int p2, long p3, java.util.Map p4) {
        }
        public static int boostForTgpa(int p0, int p1, java.lang.String p2) {
            return 0;
        }
        public static void gpApplyUserConfigForPkg(int p0, java.lang.String p1) {
        }
        public static void gpDeleteUserConfig(int p0) {
        }
        public static java.util.List gpGetAllGroupNames() {
            return new java.util.ArrayList<>();
        }
        public static java.util.List gpGetAllUserConfig() {
            return new java.util.ArrayList<>();
        }
        public static java.util.Map gpGetDispName2ValueList() {
            return new java.util.HashMap<>();
        }
        public static int gpGetUserConfigForPkg(java.lang.String p0) {
            return 0;
        }
        public static void gpNotifyExitUserConfigUI() {
        }
        public static void gpRenameUserConfig(int p0, java.lang.String p1) {
        }
        public static void gpResetUserConfig(int p0) {
        }
        public static void gpSaveUserConfig(android.os.Bundle p0, java.lang.String p1) {
        }
        public static void installEvent(android.os.Bundle p0) {
        }
        public static void noteAppAnrCrash(java.lang.String p0, int p1, java.lang.String p2, long p3) {
        }
        public static void noteAppBackground(int p0, java.lang.String p1, int p2, java.lang.String p3, long p4, int p5) {
        }
        public static void noteAppDied(java.lang.String p0) {
        }
        public static void noteAppForeground(int p0, java.lang.String p1, java.lang.String p2, int p3, java.lang.String p4, int p5, java.lang.String p6, long p7, boolean p8, android.content.Intent p9, java.lang.String p10, java.lang.String p11, boolean p12) {
        }
        public static void noteAppUsingCamera(int p0, java.lang.String p1, int p2, java.lang.String p3, long p4, boolean p5) {
        }
        public static void noteAppUsingSensor(java.lang.String p0, int p1, java.lang.String p2, long p3) {
        }
        public static void noteCommonEvent(java.lang.String p0, java.lang.String p1, android.os.Bundle p2) {
        }
        public static void noteDefendEvent(java.lang.String p0, int p1) {
        }
        public static void noteEndGps(int p0, int p1, java.lang.String p2, java.lang.String p3, long p4, long p5) {
        }
        public static void noteEndMusic(int p0, int p1, long p2) {
        }
        public static void noteEndNetworkPosition(int p0, int p1, java.lang.String p2, java.lang.String p3, long p4, long p5) {
        }
        public static void noteFocusChangedLw(java.lang.String p0) {
        }
        public static void noteIdleState(int p0) {
        }
        public static void noteLightIdleState(int p0) {
        }
        public static void notePausingActivity(int p0, int p1, java.lang.String p2, java.lang.String p3, boolean p4, java.lang.String p5, java.lang.String p6, int p7) {
        }
        public static void noteRemovingProcess(int p0, int p1, java.lang.String p2, java.lang.String p3) {
        }
        public static void noteResumedActivity(int p0, int p1, java.lang.String p2, java.lang.String p3, int p4, int p5) {
        }
        public static void noteResumedAppDie(int p0, int p1, java.lang.String p2, java.lang.String p3) {
        }
        public static void noteResumingActivity(int p0, int p1, java.lang.String p2, java.lang.String p3, boolean p4, int p5, java.lang.String p6, int p7) {
        }
        public static void noteRevokePermission(java.lang.String p0, java.lang.String p1, java.lang.String p2) {
        }
        public static void noteScreenState(int p0) {
        }
        public static void noteStartGps(int p0, int p1, java.lang.String p2, java.lang.String p3, long p4, long p5) {
        }
        public static void noteStartMusic(int p0, int p1, long p2) {
        }
        public static void noteStartNetworkPosition(int p0, int p1, java.lang.String p2, java.lang.String p3, long p4, long p5) {
        }
        public static void noteStoppingActivity(int p0, int p1, java.lang.String p2, java.lang.String p3, int p4) {
        }
        public static void noteThreadLag(java.lang.String p0, java.lang.String p1, long p2, long p3, java.lang.String p4) {
        }
        public static void noteWakeUp(java.lang.String p0, int p1, java.lang.String p2) {
        }
        public static void releasePerformanceLock(android.os.IBinder p0) {
        }
        public static void releasePerformanceLockDepend(android.os.IBinder p0) {
        }
        public static void reportEvent(int p0, android.os.IBinder p1, android.os.Bundle p2) {
        }
        public static void setAppFps(java.lang.String p0, int p1) {
        }
        public static void setFps(java.lang.String p0, java.lang.String p1, int p2, java.lang.String p3) {
        }
        public static void setNodeValue(java.lang.String p0, java.lang.String p1) {
        }
        public static void setSystemProperties(java.lang.String p0, java.lang.String p1) {
        }
        public static int setThreadCpusetForTgpa(int p0, boolean p1) {
            return 0;
        }
        public static void uninstallEvent(android.os.Bundle p0) {
        }
    }
}
