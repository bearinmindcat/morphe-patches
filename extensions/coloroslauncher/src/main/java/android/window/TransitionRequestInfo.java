package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class TransitionRequestInfo {
    public TransitionRequestInfo(int p0, android.app.ActivityManager.RunningTaskInfo p1, android.app.ActivityManager.RunningTaskInfo p2, android.window.RemoteTransition p3, android.window.TransitionRequestInfo.DisplayChange p4, int p5) {
    }
    public int getDebugId() {
        return 0;
    }
    public android.window.TransitionRequestInfo.DisplayChange getDisplayChange() {
        return null;
    }
    public int getFlags() {
        return 0;
    }
    public android.app.ActivityManager.RunningTaskInfo getPipTask() {
        return null;
    }
    public android.window.RemoteTransition getRemoteTransition() {
        return null;
    }
    public android.app.ActivityManager.RunningTaskInfo getTriggerTask() {
        return null;
    }
    public int getType() {
        return 0;
    }
    public static class DisplayChange {
        public int getDisplayId() {
            return 0;
        }
        public android.graphics.Rect getEndAbsBounds() {
            return new android.graphics.Rect();
        }
        public int getEndRotation() {
            return 0;
        }
        public android.graphics.Rect getStartAbsBounds() {
            return new android.graphics.Rect();
        }
        public int getStartRotation() {
            return 0;
        }
        public boolean isPhysicalDisplayChanged() {
            return false;
        }
    }
}
