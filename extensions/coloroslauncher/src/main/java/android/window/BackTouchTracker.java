package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class BackTouchTracker {
    public BackTouchTracker() {
    }
    public android.window.BackMotionEvent createProgressEvent() {
        return new android.window.BackMotionEvent();
    }
    public void dump(java.io.PrintWriter p0, java.lang.String p1) {
    }
    public boolean getTriggerBack() {
        return false;
    }
    public boolean isActive() {
        return false;
    }
    public boolean isFinished() {
        return false;
    }
    public boolean isInInitialState() {
        return false;
    }
    public void reset() {
    }
    public void setGestureStartLocation(float p0, float p1, int p2) {
    }
    public void setProgressThresholds(float p0, float p1, float p2) {
    }
    public void setState(android.window.BackTouchTracker.TouchTrackerState p0) {
    }
    public void setTriggerBack(boolean p0) {
    }
    public void update(float p0, float p1, float p2, float p3) {
    }
    public void updateStartLocation() {
    }
    public static class TouchTrackerState {
        public static android.window.BackTouchTracker.TouchTrackerState ACTIVE = new android.window.BackTouchTracker.TouchTrackerState();
        public static android.window.BackTouchTracker.TouchTrackerState FINISHED = new android.window.BackTouchTracker.TouchTrackerState();
    }
}
