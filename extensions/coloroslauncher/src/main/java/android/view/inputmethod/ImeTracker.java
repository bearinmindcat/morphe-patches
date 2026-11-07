package android.view.inputmethod;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public interface ImeTracker {
    public static boolean DEBUG_IME_VISIBILITY = false;
    public static android.view.inputmethod.ImeTracker forLogging() {
        return null;
    }
    void onCancelled(android.view.inputmethod.ImeTracker.Token p0, int p1);
    void onFailed(android.view.inputmethod.ImeTracker.Token p0, int p1);
    void onHidden(android.view.inputmethod.ImeTracker.Token p0);
    void onProgress(android.view.inputmethod.ImeTracker.Token p0, int p1);
    void onShown(android.view.inputmethod.ImeTracker.Token p0);
    android.view.inputmethod.ImeTracker.Token onStart(int p0, int p1, int p2, boolean p3);
    public static class Token {
        public java.lang.String getTag() {
            return "";
        }
    }
}
