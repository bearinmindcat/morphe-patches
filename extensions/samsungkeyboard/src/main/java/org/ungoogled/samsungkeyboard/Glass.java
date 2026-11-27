package org.ungoogled.samsungkeyboard;

import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Frosted glass (SBoard): the keyboard's background lets what is behind it show through, blurred where the phone blurs
 * windows (Android 12+, cross-window blur on), else as a light tint. Keys, buttons and panels laid over them keep their
 * own backgrounds.
 *
 * The keyboard's window covers the whole screen (it draws key previews and the floating keyboard anywhere), so the
 * window's background blur (Window.setBackgroundBlurRadius, which needs a translucent window theme) is kept to the
 * keyboard's background view: Android draws the blur as the first layer of the window's background, and that layer's
 * bounds are the blurred area. Where that layer can't be found, the blur goes off again (never the whole screen).
 */
public final class Glass {
    private static final String TAG = "SBoardGlass";
    /** The background's opacity over a blur, and without one (a tint the text stays readable on). */
    private static final int OVER_BLUR = 150;
    private static final int TINT = 225;
    private static final float BLUR_DP = 24f;
    /** How far outside the background a backdrop may reach (the floating keyboard's frame). */
    private static final float FRAME_DP = 24f;

    private static WeakReference<View> decorView = new WeakReference<>(null);
    private static WeakReference<Window> keyboardWindow = new WeakReference<>(null);
    private static WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
    private static WeakReference<View> background = new WeakReference<>(null);
    private static final List<Backdrop> backdrops = new ArrayList<>();
    private static final Rect area = new Rect();
    private static final int[] at = new int[2];
    private static boolean blurs;
    private static boolean failed;
    private static boolean scan = true;

    private Glass() {
    }

    /** The keyboard background's view id (layout_background; the patch makes this return it). */
    public static int backgroundId() {
        return 0;
    }

    /** Before the keyboard's service creates its window: a translucent window theme, so Android blurs behind it. */
    public static void theme(InputMethodService service) {
        try {
            if (Build.VERSION.SDK_INT < 31 || backgroundId() == 0) return;
            String pkg = service.getResources().getResourcePackageName(backgroundId());
            int style = service.getResources().getIdentifier("SBoardGlassWindow", "style", pkg);
            if (style != 0) service.setTheme(style);
        } catch (Throwable t) {
            Log.w(TAG, "theme", t);
        }
    }

    /** As the keyboard works out its insets (its window is shown or changed): set the window up. */
    public static void shown(InputMethodService service) {
        try {
            if (Build.VERSION.SDK_INT < 31 || backgroundId() == 0) return;
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (decor == null) return;
            if (decorView.get() != decor) {
                decorView = new WeakReference<>(decor);
                keyboardWindow = new WeakReference<>(window);
                WindowManager manager = service.getSystemService(WindowManager.class);
                blurs = manager.isCrossWindowBlurEnabled();
                // Off with battery saver or "Allow window-level blurs" off; the tint then gets stronger.
                manager.addCrossWindowBlurEnabledListener(service.getMainExecutor(), on -> {
                    blurs = on;
                    decor.invalidate();
                });
                if (!failed) window.setBackgroundBlurRadius(Math.round(BLUR_DP * service.getResources().getDisplayMetrics().density));
            }
            // Listeners belong to the window's current view root (a new one each time the window is added again).
            ViewTreeObserver tree = decor.getViewTreeObserver();
            if (observer.get() != tree) {
                observer = new WeakReference<>(tree);
                scan = true;
                tree.addOnGlobalLayoutListener(() -> scan = true);
                tree.addOnPreDrawListener(() -> {
                    apply(decor);
                    return true;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "shown", t);
        }
    }

    private static void apply(View decor) {
        try {
            View back = background.get();
            if (scan || back == null || !back.isAttachedToWindow()) {
                scan = false;
                back = decor.findViewById(backgroundId());
                background = new WeakReference<>(back);
                findBackdrops(decor, back);
            }
            if (!(back instanceof ImageView) || !back.isShown() || back.getWidth() == 0) {
                area.setEmpty();
            } else {
                area.set(rect(back));
                int alpha = blurs && !failed ? OVER_BLUR : TINT;
                ImageView image = (ImageView) back;
                if (image.getImageAlpha() != alpha) image.setImageAlpha(alpha);
                for (Backdrop b : backdrops) {
                    View v = b.view.get();
                    Drawable d = v == null ? null : v.getBackground();
                    if (d == null || d.getAlpha() == 0) continue;
                    if (d != b.drawable) {
                        // The keyboard gave the view a new background: remember that one's own opacity.
                        b.drawable = d.mutate();
                        b.alpha = d.getAlpha();
                    }
                    d.setAlpha(0);
                }
            }
            limit(decor);
        } catch (Throwable t) {
            Log.w(TAG, "apply", t);
        }
    }

    /** A background made see-through, with the opacity to give back. */
    private static final class Backdrop {
        final WeakReference<View> view;
        Drawable drawable;
        int alpha;

        Backdrop(View v) {
            view = new WeakReference<>(v);
            drawable = v.getBackground().mutate();
            alpha = drawable.getAlpha();
        }
    }

    private static void findBackdrops(View decor, View back) {
        List<View> found = new ArrayList<>();
        if (back instanceof ImageView && back.isShown()) {
            int frame = Math.round(FRAME_DP * back.getResources().getDisplayMetrics().density);
            Rect around = rect(back);
            around.inset(-frame, -frame);
            findBackdrops(decor, back, around, back.getWidth() * 0.9f, new ArrayList<>(), found);
        }
        // One that no longer qualifies (something now lies under it) gets its fill back.
        for (Backdrop b : backdrops) {
            View v = b.view.get();
            if (v != null && !found.contains(v) && v.getBackground() == b.drawable) b.drawable.setAlpha(b.alpha);
        }
        List<Backdrop> next = new ArrayList<>();
        for (View v : found) {
            Backdrop kept = null;
            for (Backdrop b : backdrops) if (b.view.get() == v && b.drawable == v.getBackground()) kept = b;
            next.add(kept != null ? kept : new Backdrop(v));
        }
        backdrops.clear();
        backdrops.addAll(next);
    }

    /**
     * Views that paint a plain fill across the keyboard's background (the toolbar's strip, the navigation bar's area,
     * the emoji board) with nothing drawn under them: left on, they would hide the glass, so their fill goes. Panels
     * laid over keys or buttons (the toolbar's "...", the clipboard's title bar) keep theirs, as do keys and buttons
     * (narrower). Walks in drawing order, collecting what was drawn so far in [drawn].
     */
    private static void findBackdrops(View v, View back, Rect around, float width, List<Rect> drawn, List<View> found) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f || v.getWidth() == 0 || v.getHeight() == 0) return;
        Rect r = rect(v);
        if (v != back && plain(v.getBackground()) && r.width() >= width && around.contains(r)) {
            boolean over = false;
            for (Rect d : drawn) if (Rect.intersects(d, r)) over = true;
            if (!over) found.add(v);
        }
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findBackdrops(g.getChildAt(i), back, around, width, drawn, found);
        } else if (v != back && ((!(v instanceof ViewGroup) && v.getClass() != View.class)
            || (v.getBackground() != null && !plain(v.getBackground())))) {
            // Content (text, icons, a custom view; not an empty layout or a plain fill): anything wide laid over it
            // later is a panel, not a backdrop.
            drawn.add(r);
        }
    }

    /** A plain fill: a colour or a shape, or layers of those (no images, which could be icons). */
    private static boolean plain(Drawable d) {
        if (d instanceof ColorDrawable || d instanceof GradientDrawable) return true;
        if (d instanceof DrawableWrapper) return plain(((DrawableWrapper) d).getDrawable());
        if (!(d instanceof LayerDrawable)) return false;
        LayerDrawable layers = (LayerDrawable) d;
        for (int i = 0; i < layers.getNumberOfLayers(); i++) if (!plain(layers.getDrawable(i))) return false;
        return layers.getNumberOfLayers() > 0;
    }

    private static Rect rect(View v) {
        v.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
    }

    /**
     * Keeps the window's blur layer to the keyboard background's area. With a blur, DecorView draws [blur, window
     * background] as one layer list; getBackground() answers the window background alone, whose callback is that list.
     */
    private static void limit(View decor) {
        Window window = keyboardWindow.get();
        if (failed || window == null) return;
        Drawable original = decor.getBackground();
        Drawable.Callback callback = original == null ? null : original.getCallback();
        if (original != null && callback == null && decor.isAttachedToWindow()) {
            // The blur went on: DecorView put [blur, background] in place of the background, and taking that
            // background off the view dropped its link to the list. A new window background is linked to a new list.
            window.setBackgroundDrawable(new GradientDrawable());
            original = decor.getBackground();
            callback = original == null ? null : original.getCallback();
        }
        if (callback == decor || callback instanceof DrawableWrapper) return;  // no blur layer (yet)
        if (!(callback instanceof LayerDrawable) || ((LayerDrawable) callback).getNumberOfLayers() != 2
            || ((LayerDrawable) callback).getDrawable(1) != original) {
            // Not the layout this was written for: no blur rather than a blurred screen.
            failed = true;
            window.setBackgroundBlurRadius(0);
            Log.w(TAG, "window background is " + callback + ", blur off");
            return;
        }
        LayerDrawable layers = (LayerDrawable) callback;
        Rect b = layers.getBounds().isEmpty() ? new Rect(0, 0, decor.getWidth(), decor.getHeight()) : layers.getBounds();
        int l, t, r, bo;
        if (area.isEmpty()) {
            l = 0;
            t = b.height();
            r = 0;
            bo = 0;
        } else {
            l = Math.max(0, area.left - b.left);
            t = Math.max(0, area.top - b.top);
            r = Math.max(0, b.right - area.right);
            bo = Math.max(0, b.bottom - area.bottom);
        }
        if (layers.getLayerInsetLeft(0) == l && layers.getLayerInsetTop(0) == t && layers.getLayerInsetRight(0) == r
            && layers.getLayerInsetBottom(0) == bo) return;
        layers.setLayerInset(0, l, t, r, bo);
        // A layer's bounds follow its insets only when the list's bounds change.
        Rect o = layers.copyBounds();
        layers.setBounds(o.left, o.top, o.right, o.bottom + 1);
        layers.setBounds(o);
    }
}
