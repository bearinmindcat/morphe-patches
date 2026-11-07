package org.ungoogled.samsungcapture;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    // Android does let an app set (contentInsets, TOUCHABLE_INSETS_CONTENT): the box around the controls. The recorder's
    // own windows go further (see their frames, below).

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
        if (root instanceof Fit) {
            ((Fit) root).insets(region, info);
            return;
        }
        PassThrough pass = PASS.get(root);
        if (pass != null) {
            pass.insets(region, info);
            return;
        }
        contentInsets(info, root.getWidth(), root.getHeight(), region.getBounds());
    }

    /** The touchable area as the box inside a window of this size (empty: none of it). */
    private static void contentInsets(Object info, int width, int height, Rect box) {
        try {
            Rect insets = (Rect) info.getClass().getField("contentInsets").get(info);
            if (box.isEmpty()) {
                insets.set(width, 0, 0, 0);
            } else {
                insets.set(Math.max(0, box.left), Math.max(0, box.top),
                        Math.max(0, width - box.right), Math.max(0, height - box.bottom));
            }
            touchableInsets(info, TOUCHABLE_INSETS_CONTENT);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) Log.w(Projection.TAG, "touchable area not set", e);
            warned = true;
        }
    }

    private static void touchableInsets(Object info, int mode) throws ReflectiveOperationException {
        info.getClass().getMethod("setTouchableInsets", int.class).invoke(info, mode);
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
            View window = toolbar.getParent() instanceof Fit ? (View) toolbar.getParent() : toolbar;
            ViewGroup.LayoutParams params = window.getLayoutParams();
            manager.removeViewImmediate(window);
            manager.addView(window, params);
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

    // The floating windows' frames. Android 12+ drops a touch meant for another app when windows of an untrusted app
    // cover that point with more than 80 % opacity, judged by each covering window's whole frame, not its touchable
    // area. One UI's own Samsung capture uses trusted system windows; here they are app overlays, and the recorder's
    // toolbar window is as tall as the screen (the selfie view, countdown and partial-screen frame cover all of it):
    // nothing under them could be touched while recording ("Screen touches may be delayed or not recognized" on One
    // UI). Now the toolbar's and the selfie view's windows are only as large as their controls (Fit); the countdown and
    // the partial-screen frame, which draw over the whole screen, take no touches themselves, are no more than 80 %
    // opaque, and small windows above their controls hand those touches on (PassThrough); the windows that take no
    // touches anyway stay below 80 %.

    private static final Map<View, PassThrough> PASS = new WeakHashMap<>();

    /** ViewManager.addView for the toolbar and the selfie view: the window becomes the size of its controls. */
    public static void addFittedView(ViewManager manager, View view, ViewGroup.LayoutParams params) {
        if (!isOverlay(manager, params)) {
            manager.addView(view, params);
            return;
        }
        WindowManager.LayoutParams app = (WindowManager.LayoutParams) params;
        if ((app.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            manager.addView(view, untouchable(view.getContext(), app, app.alpha));
        } else if (app.width == ViewGroup.LayoutParams.WRAP_CONTENT || app.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            manager.addView(view, params);
        } else {
            new Fit((WindowManager) manager, view, app).add();
        }
    }

    /** ViewManager.addView for the countdown and the partial-screen frame: its controls' touches come from above. */
    public static void addPassThroughView(ViewManager manager, View view, ViewGroup.LayoutParams params) {
        if (!isOverlay(manager, params)) {
            manager.addView(view, params);
            return;
        }
        WindowManager.LayoutParams app = (WindowManager.LayoutParams) params;
        if ((app.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) PASS.put(view, new PassThrough((WindowManager) manager, view));
        manager.addView(view, untouchable(view.getContext(), app, app.alpha));
    }

    /** ViewManager.updateViewLayout for the same windows. */
    public static void updateViewLayout(ViewManager manager, View view, ViewGroup.LayoutParams params) {
        if (view.getParent() instanceof Fit && params instanceof WindowManager.LayoutParams) {
            ((Fit) view.getParent()).update((WindowManager.LayoutParams) params);
        } else if (isOverlay(manager, params) && (PASS.containsKey(view)
                || (((WindowManager.LayoutParams) params).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)) {
            WindowManager.LayoutParams app = (WindowManager.LayoutParams) params;
            manager.updateViewLayout(view, untouchable(view.getContext(), app, app.alpha));
        } else {
            manager.updateViewLayout(view, params);
        }
    }

    /** ViewManager.removeView for the same windows. */
    public static void removeView(ViewManager manager, View view) {
        remove(manager, view, false);
    }

    /** WindowManager.removeViewImmediate for the same windows. */
    public static void removeViewImmediate(WindowManager manager, View view) {
        remove(manager, view, true);
    }

    private static void remove(ViewManager manager, View view, boolean immediate) {
        View window = view;
        if (view.getParent() instanceof Fit) {
            Fit fit = (Fit) view.getParent();
            fit.dead = true;
            window = fit;
        }
        PassThrough pass = PASS.remove(view);
        if (pass != null) pass.clear();
        if (immediate && manager instanceof WindowManager) {
            ((WindowManager) manager).removeViewImmediate(window);
        } else {
            manager.removeView(window);
        }
        // The content can be added again at once (the toolbar is).
        if (window != view) ((Fit) window).removeView(view);
    }

    private static boolean isOverlay(ViewManager manager, ViewGroup.LayoutParams params) {
        return manager instanceof WindowManager && params instanceof WindowManager.LayoutParams
                && ((WindowManager.LayoutParams) params).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    /** The highest opacity of other apps' windows Android still lets touches through (80 % unless changed). */
    private static float maxOpacity(Context context) {
        try {
            InputManager input = context.getSystemService(InputManager.class);
            if (input != null) return input.getMaximumObscuringOpacityForTouch();
        } catch (RuntimeException ignored) {
        }
        return 0.8f;
    }

    /** A copy of these parameters for a window that takes no touches and lets them through to the app below. */
    private static WindowManager.LayoutParams untouchable(Context context, WindowManager.LayoutParams app, float alpha) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.copyFrom(app);
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        p.alpha = Math.min(alpha, maxOpacity(context));
        return p;
    }

    /** Whether a window with this gravity grows from its right (bottom) edge. */
    private static boolean fromEnd(int gravity, int shift) {
        int axis = (gravity >> shift) & (Gravity.AXIS_PULL_BEFORE | Gravity.AXIS_PULL_AFTER);
        return axis == Gravity.AXIS_PULL_AFTER;
    }

    private static boolean sameWindow(WindowManager.LayoutParams a, WindowManager.LayoutParams b) {
        return a != null && b != null && a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height
                && a.gravity == b.gravity && a.flags == b.flags && a.alpha == b.alpha;
    }

    /**
     * The window around a floating window's content, only as large as the content's controls (its touchable region's
     * box). The content keeps the size and the place on the screen it had in the window Samsung capture asked for.
     */
    static final class Fit extends ViewGroup {
        private static final int HIDDEN = 0;  // content not shown: the window shows nothing and takes no touches
        private static final int FULL = 1;    // the window Samsung capture asked for (first frame, after a resize)
        private static final int LOOSE = 2;   // content shown without controls: the whole window, no touches
        private static final int FITTED = 3;  // the controls' box

        final WindowManager manager;
        final View content;
        WindowManager.LayoutParams app;          // Samsung capture's own parameters (it changes them in place)
        final WindowManager.LayoutParams seen = new WindowManager.LayoutParams();
        WindowManager.LayoutParams applied;
        final Rect box = new Rect();             // the window's part of the content; empty: the whole window
        int state = FULL;
        int fullWidth, fullHeight;               // the size of the window Samsung capture asked for ...
        int originX, originY, originAppX, originAppY;  // ... and its place on the screen at that x and y
        boolean originKnown;
        int fullAppX, fullAppY;                  // Samsung capture's x and y when that window was last applied
        int fixX, fixY, fixes;                   // measured offset of a fitted window from where it was asked to be
        WindowInsets fullInsets;
        int orientation, screenWidthDp, screenHeightDp;
        boolean dead, posted;
        int wantState;
        final Rect wantBox = new Rect();
        final int[] location = new int[2];

        Fit(WindowManager manager, View content, WindowManager.LayoutParams app) {
            super(content.getContext());
            this.manager = manager;
            this.content = content;
            this.app = app;
            addView(content, app);
            Configuration c = getResources().getConfiguration();
            orientation = c.orientation;
            screenWidthDp = c.screenWidthDp;
            screenHeightDp = c.screenHeightDp;
        }

        void add() {
            state = content.getVisibility() == VISIBLE ? FULL : HIDDEN;
            seen.copyFrom(app);
            applied = params(state, box);
            fullAppX = app.x;
            fullAppY = app.y;
            manager.addView(this, applied);
        }

        /** Samsung capture's updateViewLayout: moves keep the fitted window, anything else starts over from FULL. */
        void update(WindowManager.LayoutParams params) {
            if (content.getLayoutParams() != params) content.setLayoutParams(params);
            boolean resized = params.width != seen.width || params.height != seen.height || params.gravity != seen.gravity;
            app = params;
            seen.copyFrom(params);
            if (resized) {
                originKnown = false;
                if (state == FITTED || state == LOOSE) state = FULL;
                box.setEmpty();
                requestLayout();
            }
            apply(params(state, box), true);
        }

        private WindowManager.LayoutParams params(int state, Rect box) {
            WindowManager.LayoutParams p;
            switch (state) {
                case HIDDEN:
                    p = untouchable(getContext(), app, 0f);
                    break;
                case LOOSE:
                    p = untouchable(getContext(), app, app.alpha);
                    break;
                case FITTED:
                    p = new WindowManager.LayoutParams();
                    p.copyFrom(app);
                    int dx = app.x - originAppX;
                    int dy = app.y - originAppY;
                    if (fromEnd(app.gravity, Gravity.AXIS_X_SHIFT)) dx = -dx;
                    if (fromEnd(app.gravity, Gravity.AXIS_Y_SHIFT)) dy = -dy;
                    // Screen coordinates: the display, not the area between the system bars, is its parent.
                    p.gravity = Gravity.TOP | Gravity.LEFT;
                    p.x = originX + dx + box.left + fixX;
                    p.y = originY + dy + box.top + fixY;
                    p.width = box.width();
                    p.height = box.height();
                    p.horizontalMargin = 0;
                    p.verticalMargin = 0;
                    p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    p.setFitInsetsTypes(0);
                    p.setFitInsetsSides(0);
                    p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                    break;
                default:
                    p = new WindowManager.LayoutParams();
                    p.copyFrom(app);
                    break;
            }
            return p;
        }

        private void apply(WindowManager.LayoutParams p, boolean always) {
            if (dead || (!always && sameWindow(p, applied))) return;
            applied = p;
            if (state != FITTED) {
                fullAppX = app.x;
                fullAppY = app.y;
            }
            if (isAttachedToWindow()) manager.updateViewLayout(this, p);
        }

        /** onComputeInternalInsets of the content's window, with the region Samsung capture filled in. */
        void insets(Region region, Object info) {
            Rect controls = region.getBounds();
            if (!controls.intersect(0, 0, fullWidth, fullHeight)) controls.setEmpty();
            boolean whole = box.isEmpty();
            try {
                if (state == FITTED) {
                    touchableInsets(info, 0); // TOUCHABLE_INSETS_FRAME: all of the fitted window
                } else {
                    contentInsets(info, getWidth(), getHeight(), state == FULL ? controls : new Rect());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(Projection.TAG, "touchable area not set", e);
            }
            getLocationOnScreen(location);
            if (whole) {
                // The window Samsung capture asked for is on the screen as asked: where it is.
                originX = location[0];
                originY = location[1];
                originAppX = fullAppX;
                originAppY = fullAppY;
                originKnown = getWidth() > 0 && getHeight() > 0;
            } else if (state == FITTED && applied != null && fixes < 2
                    && (location[0] != applied.x || location[1] != applied.y)) {
                // Not where it was asked to be (an offset the parameters do not show): ask again, corrected.
                fixX += applied.x - location[0];
                fixY += applied.y - location[1];
                fixes++;
                Log.i(Projection.TAG, "fitted window corrected by " + fixX + "," + fixY);
                want(FITTED, box);
                return;
            }
            int target;
            if (content.getVisibility() != VISIBLE) target = HIDDEN;
            else if (controls.isEmpty()) target = LOOSE;
            else if (originKnown) target = FITTED;
            else target = FULL;
            if (target != state || (target == FITTED && !controls.equals(box))) want(target, controls);
        }

        /** Changes the window after the current frame, not inside it. */
        private void want(int target, Rect controls) {
            wantState = target;
            wantBox.set(controls);
            if (posted) return;
            posted = true;
            post(() -> {
                posted = false;
                if (dead) return;
                state = wantState;
                if (state == FITTED) box.set(wantBox);
                else box.setEmpty();
                requestLayout();
                apply(params(state, box), false);
            });
        }

        @Override
        protected void onConfigurationChanged(Configuration c) {
            super.onConfigurationChanged(c);
            if (c.orientation == orientation && c.screenWidthDp == screenWidthDp && c.screenHeightDp == screenHeightDp) return;
            orientation = c.orientation;
            screenWidthDp = c.screenWidthDp;
            screenHeightDp = c.screenHeightDp;
            // Rotated or resized: measure the asked-for window again.
            originKnown = false;
            fixX = fixY = fixes = 0;
            if (state == FITTED || state == LOOSE) want(FULL, new Rect());
        }

        @Override
        public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
            // The content keeps the insets of the window it asked for (the selfie view places itself by them).
            if (box.isEmpty() || fullInsets == null) fullInsets = insets;
            return super.dispatchApplyWindowInsets(box.isEmpty() ? insets : fullInsets);
        }

        @Override
        protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
            return p != null; // the content keeps Samsung capture's own WindowManager.LayoutParams
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            if (box.isEmpty()) {
                fullWidth = width;
                fullHeight = height;
            }
            content.measure(MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.EXACTLY));
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            content.layout(-box.left, -box.top, fullWidth - box.left, fullHeight - box.top);
        }
    }

    /** A window drawn over the whole screen that takes no touches: its controls' touches come from small windows. */
    static final class PassThrough {
        final WindowManager manager;
        final View content;
        final List<Proxy> proxies = new ArrayList<>();
        final List<Rect> rects = new ArrayList<>();
        final List<Rect> want = new ArrayList<>();
        boolean posted;

        PassThrough(WindowManager manager, View content) {
            this.manager = manager;
            this.content = content;
        }

        void insets(Region region, Object info) {
            try {
                touchableInsets(info, 0);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            List<Rect> next = new ArrayList<>();
            if (content.isShown() && !region.isEmpty()) {
                RegionIterator it = new RegionIterator(region);
                Rect r = new Rect();
                while (it.next(r)) {
                    if (!r.isEmpty()) next.add(new Rect(r));
                }
                // A region is stored as horizontal bands: rects that touch are one control again.
                for (boolean merged = true; merged; ) {
                    merged = false;
                    for (int i = 0; i < next.size() && !merged; i++) {
                        for (int j = i + 1; j < next.size(); j++) {
                            Rect a = next.get(i);
                            Rect b = next.get(j);
                            if (a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom) {
                                a.union(b);
                                next.remove(j);
                                merged = true;
                                break;
                            }
                        }
                    }
                }
                if (next.size() > 8) {
                    next.clear();
                    next.add(region.getBounds());
                }
            }
            if (next.equals(rects) && !posted) return;
            want.clear();
            want.addAll(next);
            if (posted) return;
            posted = true;
            content.post(() -> {
                posted = false;
                if (PASS.get(content) == this) sync();
            });
        }

        private void sync() {
            int[] origin = new int[2];
            content.getLocationOnScreen(origin);
            for (int i = 0; i < want.size(); i++) {
                Rect r = want.get(i);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(r.width(), r.height(),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSPARENT);
                p.gravity = Gravity.TOP | Gravity.LEFT;
                p.x = origin[0] + r.left;
                p.y = origin[1] + r.top;
                p.setFitInsetsTypes(0);
                p.setFitInsetsSides(0);
                p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                p.setTitle("Samsung capture controls");
                try {
                    if (i < proxies.size()) {
                        Proxy proxy = proxies.get(i);
                        if (!sameWindow(p, proxy.params)) {
                            proxy.params = p;
                            manager.updateViewLayout(proxy, p);
                        }
                    } else {
                        Proxy proxy = new Proxy(content);
                        proxy.params = p;
                        manager.addView(proxy, p);
                        proxies.add(proxy);
                    }
                } catch (RuntimeException e) {
                    Log.w(Projection.TAG, "control window not placed", e);
                }
            }
            while (proxies.size() > want.size()) {
                Proxy proxy = proxies.remove(proxies.size() - 1);
                try {
                    manager.removeViewImmediate(proxy);
                } catch (RuntimeException ignored) {
                }
            }
            rects.clear();
            rects.addAll(want);
        }

        void clear() {
            want.clear();
            sync();
        }
    }

    /** A transparent window over one of a PassThrough window's controls, handing it its touches. */
    static final class Proxy extends View {
        final View target;
        WindowManager.LayoutParams params;
        private final int[] origin = new int[2];

        Proxy(View target) {
            super(target.getContext());
            this.target = target;
            setWillNotDraw(true);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (!target.isAttachedToWindow()) return false;
            target.getLocationOnScreen(origin);
            MotionEvent copy = MotionEvent.obtain(event);
            // Into the target window's coordinates, from the event's own screen position.
            copy.offsetLocation(event.getRawX() - event.getX() - origin[0], event.getRawY() - event.getY() - origin[1]);
            try {
                target.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
            return true;
        }
    }
}
