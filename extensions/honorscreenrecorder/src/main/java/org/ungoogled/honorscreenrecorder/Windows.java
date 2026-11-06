package org.ungoogled.honorscreenrecorder;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.Window;
import android.view.WindowManager;

/**
 * The recorder's windows (timer, floating controls, the saved-video thumbnail, its dialogs) are system windows on an
 * Honor phone (system error, keyguard dialog, screenshot and system alert types, trusted overlays), which only a
 * system app may add. Here they are app overlays ("Display over other apps", which the recorder asks for). An
 * overlay that lets touches through to other apps is kept at Android's highest opacity for that (0.8): Android
 * blocks touches under a more opaque overlay of another app.
 */
public final class Windows {
    private static final float MAX_PASS_THROUGH_ALPHA = 0.8f;

    private Windows() {
    }

    private static int type(int type) {
        if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return type;
    }

    private static void fix(ViewGroup.LayoutParams params) {
        if (!(params instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams window = (WindowManager.LayoutParams) params;
        window.type = type(window.type);
        if (window.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                && (window.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
                && window.alpha > MAX_PASS_THROUGH_ALPHA) {
            window.alpha = MAX_PASS_THROUGH_ALPHA;
        }
    }

    public static void addView(ViewManager manager, View view, ViewGroup.LayoutParams params) {
        fix(params);
        manager.addView(view, params);
    }

    public static void updateViewLayout(ViewManager manager, View view, ViewGroup.LayoutParams params) {
        fix(params);
        manager.updateViewLayout(view, params);
    }

    public static void setType(Window window, int type) {
        window.setType(type(type));
    }

    /** "Modify system settings": the recorder only uses it for Show touches, a setting Android keeps to itself. */
    public static boolean canWrite(android.content.Context context) {
        return true;
    }
}
