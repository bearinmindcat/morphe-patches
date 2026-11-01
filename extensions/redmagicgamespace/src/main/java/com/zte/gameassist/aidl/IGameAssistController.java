package com.zte.gameassist.aidl;

/** Generated stand-in (.github/scripts/redmagic-standins): names and signatures RedMagic Game Space uses, answering "absent" off RedMagic OS (no Nubia or ZTE code). */
public interface IGameAssistController {
    java.util.List getGameLauncherAppNameList();
    java.util.List getMutexTags(java.lang.String p0);
    void invake(java.lang.String p0, android.os.Bundle p1, com.zte.gameassist.aidl.ICallback p2);
    void monitorMutexTag(boolean p0, java.lang.String p1, com.zte.gameassist.aidl.ICallback p2);
    void mutexTag(boolean p0, java.lang.String p1, java.lang.String p2, android.os.IBinder p3);
    public static class Default {
        public Default() {
        }
    }
    public static abstract class Stub extends android.os.Binder implements com.zte.gameassist.aidl.IGameAssistController {
        public Stub() {
        }
        public static com.zte.gameassist.aidl.IGameAssistController asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
