package org.ungoogled.samsungcapture;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Samsung capture's floating windows (the recorder's controls, countdown, drawing layer and sound question) use window
 * types only the system may add (TYPE_SCREENSHOT, Samsung's own), which it looks up by name. A regular app has one
 * window type over other apps: TYPE_APPLICATION_OVERLAY, with "Display over other apps" allowed.
 */
public final class Windows {
    private Windows() {
    }

    /** DeviceUtils.getWindowType(name). */
    public static int type(String name) {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    /** SepWrapper.Context.createWindowContext(context, type, options): system types become the overlay type. */
    public static Context createWindowContext(Context context, int type, Bundle options) {
        if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return context.createWindowContext(type, options);
    }

    /**
     * Window.setType: dialogs Samsung capture raises as system windows (the editor's "All files access" question, the
     * recorder's warnings). Over one of its own screens a dialog keeps its normal type; elsewhere it becomes an app
     * overlay.
     */
    public static void setType(android.view.Window window, int type) {
        if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW && type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            Context context = window.getContext();
            while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity)) {
                Context base = ((android.content.ContextWrapper) context).getBaseContext();
                if (base == null || base == context) break;
                context = base;
            }
            if (context instanceof android.app.Activity) return;
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        window.setType(type);
    }

    // The floating windows' touchable area. Each window is larger than what it shows (the recorder's toolbar window is
    // as tall as the screen), and Samsung capture limits its touches to the controls through the window's touchable
    // region (ViewTreeObserver.InternalInsetsInfo.touchableRegion), a field Android refuses an app: the whole window
    // took every touch, so nothing under it could be used while recording. The same limit is set through the insets
    // Android does let an app set (contentInsets, TOUCHABLE_INSETS_CONTENT): the box around the controls.

    private static final int TOUCHABLE_INSETS_CONTENT = 1;
    private static final Map<Object, Object> LISTENERS = new WeakHashMap<>();
    private static final Map<Object, Region> REGIONS = new WeakHashMap<>();
    private static boolean warned;

    /** onComputeInternalInsets, first: which listener (and so which window) these insets are for. */
    public static void beginInsets(Object listener, Object info) {
        synchronized (LISTENERS) {
            LISTENERS.put(info, listener);
            REGIONS.remove(info);
        }
    }

    /** InternalInsetsInfo.touchableRegion: a region of our own that the listener fills in. */
    public static Region touchableRegionOf(Object reflection, Object info) {
        Region region = new Region();
        synchronized (LISTENERS) {
            REGIONS.put(info, region);
        }
        return region;
    }

    /** InternalInsetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION): the filled-in region's box becomes the insets. */
    public static void setTouchableInsets(Object reflection, Object info, int mode) {
        Region region;
        Object listener;
        synchronized (LISTENERS) {
            region = REGIONS.remove(info);
            listener = LISTENERS.get(info);
        }
        if (region == null || listener == null) return;
        View root = root(listener);
        if (root == null) return;
        try {
            int width = root.getWidth();
            int height = root.getHeight();
            Rect box = region.getBounds();
            Rect insets = (Rect) info.getClass().getField("contentInsets").get(info);
            if (box.isEmpty()) {
                insets.set(width, 0, 0, 0);
            } else {
                insets.set(Math.max(0, box.left), Math.max(0, box.top),
                        Math.max(0, width - box.right), Math.max(0, height - box.bottom));
            }
            info.getClass().getMethod("setTouchableInsets", int.class).invoke(info, TOUCHABLE_INSETS_CONTENT);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) Log.w(Projection.TAG, "touchable area not set", e);
            warned = true;
        }
    }

    /** The object a listener belongs to (its outer instance). */
    private static Object owner(Object listener) throws IllegalAccessException {
        for (Class<?> c = listener.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().startsWith("this$")) {
                    f.setAccessible(true);
                    return f.get(listener);
                }
            }
        }
        return null;
    }

    /**
     * AnnotationView.show, after its drawing layer's window is added: One UI puts that layer under app overlays, below
     * the recorder's toolbar; here both are app overlays and the newer one is on top, so the layer covered the toolbar
     * (no stop or pen button while drawing). The toolbar's window is added again, on top, with its touchable area.
     */
    public static void raiseRecorderToolbar() {
        Object toolbarOwner = null;
        try {
            synchronized (LISTENERS) {
                for (Object listener : LISTENERS.values()) {
                    Object owner = owner(listener);
                    if (owner != null && owner.getClass().getName().endsWith(".screenrecorder.view.FloatingButtonView")) {
                        toolbarOwner = owner;
                        break;
                    }
                }
            }
            if (toolbarOwner == null) return;
            Class<?> c = toolbarOwner.getClass();
            Field toolbarField = c.getDeclaredField("mToolbarLayout");
            Field managerField = c.getDeclaredField("mWindowManager");
            toolbarField.setAccessible(true);
            managerField.setAccessible(true);
            View toolbar = (View) toolbarField.get(toolbarOwner);
            WindowManager manager = (WindowManager) managerField.get(toolbarOwner);
            if (toolbar == null || manager == null || !toolbar.isAttachedToWindow()) return;
            android.view.ViewGroup.LayoutParams params = toolbar.getLayoutParams();
            manager.removeViewImmediate(toolbar);
            manager.addView(toolbar, params);
            java.lang.reflect.Method insets = c.getDeclaredMethod("initComputeInternalInsetsListener");
            insets.setAccessible(true);
            insets.invoke(toolbarOwner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(Projection.TAG, "recorder toolbar not raised", e);
        }
    }

    /** The root of the window the listener's owner draws in: the root most of its attached views share. */
    private static View root(Object listener) {
        try {
            Object owner = owner(listener);
            if (owner == null) return null;
            Map<View, Integer> votes = new HashMap<>();
            for (Field f : owner.getClass().getDeclaredFields()) {
                if (!View.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                View view = (View) f.get(owner);
                if (view == null || !view.isAttachedToWindow()) continue;
                View root = view.getRootView();
                Integer n = votes.get(root);
                votes.put(root, n == null ? 1 : n + 1);
            }
            View best = null;
            int most = 0;
            for (Map.Entry<View, Integer> e : votes.entrySet()) {
                if (e.getValue() > most) {
                    most = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
