package org.ungoogled.samsungonehand;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One Hand Operation + draws its handles and panels as One UI system windows (window types only the system may add).
 * The patch sends every addView / updateViewLayout / removeView in the app here: such a window is added as an
 * accessibility overlay through the accessibility service instead, which needs no system permission; every other
 * window goes through unchanged. Without the service the window is held back (the app is told nothing failed), and
 * the service rebuilds the app's windows when it connects.
 */
public final class Windows {
    private static final int FIRST_SYSTEM_WINDOW = 2000;
    private static final int TYPE_ACCESSIBILITY_OVERLAY = 2032;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final float MIN_ALPHA = 0.01f;

    /** Windows added through the accessibility service, and the WindowManager that holds each. */
    private static final Map<View, WindowManager> ADDED = Collections.synchronizedMap(new WeakHashMap<>());

    private Windows() {
    }

    private static boolean system(ViewGroup.LayoutParams lp) {
        if (!(lp instanceof WindowManager.LayoutParams)) return false;
        int type = ((WindowManager.LayoutParams) lp).type;
        return type >= FIRST_SYSTEM_WINDOW && type != TYPE_APPLICATION_OVERLAY;
    }

    /**
     * As an accessibility overlay. A handle the user made fully transparent is a window with alpha 0, which One UI
     * still hands touches to and Android does not: such a touchable window keeps a trace of alpha instead.
     */
    private static WindowManager.LayoutParams overlay(WindowManager.LayoutParams params) {
        params.type = TYPE_ACCESSIBILITY_OVERLAY;
        if (params.alpha >= MIN_ALPHA || (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return params;
        // A copy, so the app still sees the alpha it set.
        WindowManager.LayoutParams shown = new WindowManager.LayoutParams();
        shown.copyFrom(params);
        shown.alpha = MIN_ALPHA;
        return shown;
    }

    private static boolean attached(View view) {
        return view.isAttachedToWindow() || view.getParent() != null;
    }

    public static void add(ViewManager wm, View view, ViewGroup.LayoutParams lp) {
        if (!(wm instanceof WindowManager) || !system(lp)) {
            wm.addView(view, lp);
            return;
        }
        ActionService service = ActionService.instance;
        if (service == null) {
            OneHand.debug("window held back until the accessibility service is on");
            return;
        }
        WindowManager own = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = overlay((WindowManager.LayoutParams) lp);
        Object insets = Insets.take(view);
        own.addView(view, params);
        ADDED.put(view, own);
        if (insets != null) Insets.drive(view, insets);
    }

    public static void update(ViewManager wm, View view, ViewGroup.LayoutParams lp) {
        WindowManager own = ADDED.get(view);
        if (own != null) {
            ViewGroup.LayoutParams params = lp instanceof WindowManager.LayoutParams ? overlay((WindowManager.LayoutParams) lp) : lp;
            if (attached(view)) own.updateViewLayout(view, params);
            return;
        }
        // A system window held back: nothing to update.
        if (wm instanceof WindowManager && system(lp) && !attached(view)) return;
        wm.updateViewLayout(view, lp);
    }

    public static void remove(ViewManager wm, View view) {
        WindowManager own = ADDED.remove(view);
        if (own != null) {
            if (attached(view)) own.removeView(view);
            return;
        }
        if (wm instanceof WindowManager && !attached(view)) return;
        wm.removeView(view);
    }

    public static void removeImmediate(WindowManager wm, View view) {
        WindowManager own = ADDED.remove(view);
        if (own != null) {
            if (attached(view)) own.removeViewImmediate(view);
            return;
        }
        if (!attached(view)) return;
        wm.removeViewImmediate(view);
    }

    /**
     * A dialog the app shows as a system window (its floating navigation buttons) adds itself through the
     * WindowManager of the Context it was made with: outside an activity, that is the accessibility service's.
     */
    public static Context dialogContext(Context c) {
        if (c instanceof android.app.Activity) return c;
        return new android.content.ContextWrapper(c) {
            @Override
            public Object getSystemService(String name) {
                ActionService service = ActionService.instance;
                if (Context.WINDOW_SERVICE.equals(name) && service != null) return service.getSystemService(name);
                return super.getSystemService(name);
            }
        };
    }

    /** Window.setAttributes for such a dialog: its system window type becomes an accessibility overlay. */
    public static void attributes(android.view.Window window, WindowManager.LayoutParams lp) {
        window.setAttributes(system(lp) ? overlay(lp) : lp);
    }

    /** The service went away, and with it every overlay it held. */
    static void forget() {
        ADDED.clear();
    }
}
