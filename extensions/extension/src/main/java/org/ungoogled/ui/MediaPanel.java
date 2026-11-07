package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

/**
 * Navigation's media panel (issue #136). Opening it grows the bottom sheet by the media card; Maps lifts the speedometer
 * on the left above it but leaves the button column on the right where it was, so the card covers the column's lower
 * buttons -- among them the media button, the only control that closes the panel again. While the panel is open (or
 * closing) the column follows the sheet up, frame by frame with Maps' own sheet animation, by as much as the sheet rose.
 */
public final class MediaPanel {
    /** id/nav_sheet, id/header (the sheet's ETA row) and id/media_app_remote_control (the card): pinned, names do not survive a merged APK. */
    private static final int NAV_SHEET = 0x7f0b06c5, HEADER = 0x7f0b04a5, MEDIA_CARD = 0x7f0b062a;
    /** string/MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION, string/EXPAND_...: the media button's two labels. */
    private static final int MINIMIZE = 0x7f14136e, EXPAND = 0x7f140c75;

    private static WeakReference<View> decor = new WeakReference<>(null);
    private static WeakReference<View> button = new WeakReference<>(null), column = new WeakReference<>(null);
    private static WeakReference<View> sheet = new WeakReference<>(null);
    private static String minimize, expand;
    private static boolean lifting;

    private MediaPanel() {}

    /** The media button's state changed (or was set), with the button's Context. */
    public static void changed(Object context) {
        try {
            Activity a = activityOf(context instanceof Context ? (Context) context : null);
            if (a == null) return;
            View d = a.getWindow().getDecorView();
            if (minimize == null) {
                minimize = a.getString(MINIMIZE);
                expand = a.getString(EXPAND);
            }
            if (decor.get() != d) {
                decor = new WeakReference<>(d);
                d.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        if (decor.get() != d) {
                            d.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        follow();
                        return true;
                    }
                });
            }
            // Its views may be new (a new navigation): look again once they are laid out.
            d.post(MediaPanel::find);
            d.invalidate();
        } catch (Throwable t) {
            android.util.Log.w("UA", "media panel", t);
        }
    }

    private static void find() {
        View d = decor.get();
        if (d == null) return;
        View b = withLabel(d);
        View s = d.findViewById(NAV_SHEET);
        if (b == null || s == null) return;
        View c = null;
        for (ViewParent p = b.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL) {
                c = (View) p;
                break;
            }
        }
        if (c == null) return;
        View old = column.get();
        if (old != null && old != c) old.setTranslationY(0);
        button = new WeakReference<>(b);
        column = new WeakReference<>(c);
        sheet = new WeakReference<>(s);
        d.invalidate();
    }

    /** The media button: the view labelled Expand or Minimize media controls. */
    private static View withLabel(View v) {
        CharSequence label = v.getContentDescription();
        if (label != null && (label.toString().equals(minimize) || label.toString().equals(expand))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = withLabel(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Before every frame: the column as high as the sheet rose (up to the card's height) while the panel is open. */
    private static void follow() {
        View c = column.get(), b = button.get(), s = sheet.get();
        if (c == null || b == null || s == null) return;
        if (!c.isAttachedToWindow() || !s.isAttachedToWindow()) {
            c.setTranslationY(0);
            column = new WeakReference<>(null);
            return;
        }
        CharSequence label = b.getContentDescription();
        boolean open = label != null && label.toString().equals(minimize);
        float lift = 0;
        View header = s.findViewById(HEADER), card = s.findViewById(MEDIA_CARD);
        if ((open || lifting) && header != null && card != null && header.isShown()) {
            // The sheet itself is taller than the screen and slides; it rests on its parent's bottom edge.
            int[] at = new int[2];
            View rest = s.getParent() instanceof View ? (View) s.getParent() : s.getRootView();
            rest.getLocationOnScreen(at);
            android.graphics.Rect frame = new android.graphics.Rect();
            s.getWindowVisibleDisplayFrame(frame);
            int sheetBottom = Math.min(at[1] + rest.getHeight(), frame.bottom);
            header.getLocationOnScreen(at);
            int headerLeft = at[0], headerRight = at[0] + header.getWidth(), headerTop = at[1];
            c.getLocationOnScreen(at);
            boolean beside = at[0] >= headerRight || at[0] + c.getWidth() <= headerLeft;
            // How far the sheet stands above its resting height (the header alone).
            float rose = (sheetBottom - header.getHeight()) - headerTop;
            if (!beside && rose > 0) lift = -Math.min(rose, card.getHeight());
        }
        lifting = open || lift < -1;
        if (Math.abs(c.getTranslationY() - lift) > 0.5f) {
            if (android.util.Log.isLoggable("UA", android.util.Log.DEBUG)) android.util.Log.d("UA", "media panel: column " + lift + (open ? " (open)" : ""));
            c.setTranslationY(lift);
        }
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
