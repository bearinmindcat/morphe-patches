package org.ungoogled.samsunggallery;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Samsung's Galaxy Store dialog ("Can't download right now", MissingPackage) against the editor choosers Gallery opens
 * instead of Photo Editor and Editor Lite off One UI. On an OPPO phone with Android 16 one Edit tap showed both: a
 * chooser and, behind it, the dialog from another of Gallery's paths. Within a few seconds of a chooser the dialog is
 * not shown, and one shown just before is closed.
 */
public final class StoreGuides {
    private static final String TAG = "UA";
    private static final long WINDOW_MS = 3000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long fellBackAt = Long.MIN_VALUE / 2;
    private static volatile long shownAt = Long.MIN_VALUE / 2;
    private static volatile WeakReference<Dialog> shown = new WeakReference<>(null);

    private StoreGuides() {
    }

    /** MissingPackage.showGotoStoreIfDeleted: which app is missing, and who asked (the dialog itself is posted). */
    public static void requested(String packageName) {
        String caller = "?";
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("com.samsung.android.gallery") && !c.contains("MissingPackage") && !c.endsWith("BaseCommand")) {
                caller = c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName();
                break;
            }
        }
        Log.i(TAG, "galaxy store guide for " + packageName + " from " + caller);
    }

    /** The dialog's show(): not within a few seconds of an editor chooser. */
    public static void show(AlertDialog dialog) {
        if (SystemClock.elapsedRealtime() - fellBackAt < WINDOW_MS) {
            Log.i(TAG, "galaxy store guide skipped: an editor chooser just opened");
            return;
        }
        shown = new WeakReference<>(dialog);
        shownAt = SystemClock.elapsedRealtime();
        dialog.show();
    }

    /** An editor chooser (or the "no apps" message) took the place of Photo Editor or Editor Lite. */
    static void fellBack() {
        long now = SystemClock.elapsedRealtime();
        fellBackAt = now;
        Dialog dialog = shown.get();
        if (dialog == null || now - shownAt >= WINDOW_MS) return;
        MAIN.post(() -> {
            if (dialog.isShowing()) {
                Log.i(TAG, "galaxy store guide closed: an editor chooser opened");
                dialog.dismiss();
            }
        });
    }
}
