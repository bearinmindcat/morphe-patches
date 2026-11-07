package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class TransitionFilter {
    public TransitionFilter() {
    }
    public static android.os.Parcelable.Creator CREATOR;
    public int mNotFlags;
    public android.window.TransitionFilter.Requirement[] mRequirements;
    public boolean matches(android.window.TransitionInfo p0) {
        return false;
    }
    public static class Requirement {
        public Requirement() {
        }
        public int mActivityType;
        public int[] mModes;
        public int mOrder;
        public android.content.ComponentName mTopActivity;
    }
}
