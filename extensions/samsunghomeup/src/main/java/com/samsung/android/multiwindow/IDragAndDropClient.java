package com.samsung.android.multiwindow;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Home Up uses, answering "absent" off One UI (no Samsung code). */
public interface IDragAndDropClient {
    android.graphics.Rect getHiddenDropTargetArea();
    boolean getInitialDropTargetVisible();
    void onConnected(android.os.IBinder p0, int p1);
    void onDisconnected();
    public static abstract class Stub extends android.os.Binder implements com.samsung.android.multiwindow.IDragAndDropClient {
        public Stub() {
        }
        public static com.samsung.android.multiwindow.IDragAndDropClient asInterface(android.os.IBinder p0) {
            return null;
        }
    }
}
