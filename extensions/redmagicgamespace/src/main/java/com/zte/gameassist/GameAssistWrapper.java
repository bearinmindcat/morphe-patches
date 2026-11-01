package com.zte.gameassist;

/** Generated stand-in (.github/scripts/redmagic-standins): names and signatures RedMagic Game Space uses, answering "absent" off RedMagic OS (no Nubia or ZTE code). */
public class GameAssistWrapper {
    public GameAssistWrapper() {
    }
    public static com.zte.gameassist.aidl.IGameAssistController getGameAssistController() {
        return null;
    }
    public static com.zte.gameassist.GameAssistWrapper getInstance() {
        return new com.zte.gameassist.GameAssistWrapper();
    }
    public static java.util.List getMutexTags(java.lang.String p0) {
        return new java.util.ArrayList<>();
    }
    public void invake(java.lang.String p0, android.os.Bundle p1, com.zte.gameassist.GameAssistWrapper.Callback p2) {
    }
    public void monitorMutexTag(boolean p0, java.lang.String p1, com.zte.gameassist.GameAssistWrapper.MutexCallback p2) {
    }
    public void mutexTag(boolean p0, java.lang.String p1, java.lang.String p2) {
    }
    public boolean sendToGameAssistServices(java.lang.String p0, android.os.Bundle p1, com.zte.gameassist.GameAssistWrapper.Callback p2) {
        return false;
    }
    public interface Callback {
    }
    public static class MutexCallback {
        public MutexCallback() {
        }
    }
}
