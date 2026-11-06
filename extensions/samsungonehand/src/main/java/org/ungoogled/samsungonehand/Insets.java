package org.ungoogled.samsungonehand;

import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.view.AttachedSurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The handle windows are as tall as the screen; only the handles themselves take touches. One UI lets the app say
 * so through the window's internal insets (a hidden ViewTreeObserver listener filling a hidden field), which Android
 * refuses an app. Here the app's listener is kept off the window (the patch sends its registration to
 * {@link #register}), asked for the region before each frame, and the region is set with Android's public
 * AttachedSurfaceControl.setTouchableRegion; the listener's hidden field and call come here too.
 */
public final class Insets {
    private static final int TOUCHABLE_INSETS_REGION = 3;

    /** Listeners the app registered on a not yet shown window, by that window's ViewTreeObserver. */
    private static final Map<Object, Object> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** The region the listener being asked fills, and the mode it then sets. */
    private static Region filling = new Region();
    private static int mode;

    private Insets() {
    }

    /** The app's reflective ViewTreeObserver.addOnComputeInternalInsetsListener(listener): kept for {@link #take}. */
    public static Object register(Object helper, Object observer, String name, Class<?>[] types, Object[] args) {
        if (observer != null && args != null && args.length == 1) {
            if (name != null && name.startsWith("remove")) PENDING.remove(observer);
            else PENDING.put(observer, args[0]);
        }
        return null;
    }

    /** The listener for a window about to be shown, if the app registered one. */
    static Object take(View root) {
        return PENDING.remove(root.getViewTreeObserver());
    }

    /** The listener's InternalInsetsInfo.touchableRegion. */
    public static Region region(Object info) {
        filling.setEmpty();
        return filling;
    }

    /** The listener's InternalInsetsInfo.setTouchableInsets(mode). */
    public static void touchable(Object info, int value) {
        mode = value;
    }

    /** Before each frame of the shown window: the listener's region becomes the window's touchable region. */
    static void drive(View root, Object listener) {
        Method compute = null;
        for (Method m : listener.getClass().getMethods()) {
            if (m.getName().equals("onComputeInternalInsets") && m.getParameterTypes().length == 1) compute = m;
        }
        if (compute == null) return;
        Method onCompute = compute;
        Region[] applied = {null};
        boolean[] set = {false};
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!root.isAttachedToWindow()) return true;
                Region region;
                try {
                    mode = 0;
                    filling = new Region();
                    onCompute.invoke(listener, (Object) null);
                    region = mode == TOUCHABLE_INSETS_REGION ? filling : null;
                } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                    OneHand.debug("touchable region not computed: " + e);
                    return true;
                }
                if (set[0] && (region == null ? applied[0] == null : region.equals(applied[0]))) return true;
                AttachedSurfaceControl surface = root.getRootSurfaceControl();
                if (surface == null) return true;
                surface.setTouchableRegion(region);
                // Gesture navigation's back swipe would take a swipe that starts on a handle at the screen's edge:
                // the handles are kept from it (Android grants up to 200 dp of each edge).
                List<Rect> rects = new ArrayList<>();
                if (region != null) {
                    RegionIterator parts = new RegionIterator(region);
                    Rect part = new Rect();
                    while (parts.next(part)) rects.add(new Rect(part));
                }
                root.setSystemGestureExclusionRects(rects);
                applied[0] = region;
                set[0] = true;
                return true;
            }
        });
    }
}
