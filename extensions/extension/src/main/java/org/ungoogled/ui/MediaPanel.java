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
    /** id/nav_sheet, id/header (the sheet's ETA row) and id/media_app_remote_control (the card), set by the patches (Res). */
    private static final int NAV_SHEET = Res.id_nav_sheet, HEADER = Res.id_header, MEDIA_CARD = Res.id_media_app_remote_control;
    /** string/MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION, string/EXPAND_...: the media button's two labels. */
    private static final int MINIMIZE = Res.string_MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION,
            EXPAND = Res.string_EXPAND_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION;

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

    private static long lookedAt;

    /** Before every frame: the column as high as the sheet rose (up to the card's height) while the panel is open. */
    private static void follow() {
        View c = column.get(), b = button.get(), s = sheet.get();
        if (c == null || b == null || s == null || !c.isAttachedToWindow() || !b.isAttachedToWindow() || !s.isAttachedToWindow()) {
            // Maps takes the navigation views off the window while it is picture-in-picture and puts the same ones
            // back after (issue #78): the column, back without its lift, sat down behind the media card until the
            // button changed again. Look for them again (twice a second at most) instead of waiting for that.
            if (c != null && !c.isAttachedToWindow()) c.setTranslationY(0);
            column = new WeakReference<>(null);
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lookedAt > 500) {
                lookedAt = now;
                find();
            }
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

    /**
     * The media card's part of the lowered navigation sheet, its measured height as Maps would use it (issue #78): the
     * card is at least 124 dp tall and keeps room under its Browse row, and the sheet also shows under the navigation
     * bar, so the lowered sheet showed part of what follows the card (the other media apps' row). It now ends a few
     * pixels below the Browse row, and never so low that what follows the card shows under the navigation bar.
     */
    public static int cardPeek(int measured) {
        try {
            View d = decor.get();
            View card = d == null ? null : d.findViewById(MEDIA_CARD);
            if (!(card instanceof ViewGroup) || card.getMeasuredHeight() != measured || !card.isLaidOut()) return measured;
            if (browse == null) browse = card.getContext().getString(BROWSE);
            View row = null;
            ViewGroup g = (ViewGroup) card;
            for (int i = 0; i < g.getChildCount() && row == null; i++) {
                View child = g.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.getBottom() > 0 && hasText(child, browse)) row = child;
            }
            if (row == null) return measured;
            float density = card.getResources().getDisplayMetrics().density;
            int peek = row.getBottom() + Math.round(4 * density);
            // What follows the card in the sheet, relative to the card's top, and the navigation bar the sheet runs under.
            int[] at = new int[2];
            card.getLocationOnScreen(at);
            int cardTop = at[1];
            View sheet = d.findViewById(NAV_SHEET);
            int next = sheet == null ? Integer.MAX_VALUE : nextBelow(sheet, card, cardTop + card.getHeight()) - cardTop;
            int bar = 0;
            android.view.WindowInsets insets = d.getRootWindowInsets();
            if (insets != null) bar = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            if (peek + bar > next - 1) peek = Math.max(row.getBottom(), next - 1 - bar);
            if (android.util.Log.isLoggable("UA", android.util.Log.DEBUG)) {
                android.util.Log.d("UA", "media panel: card " + measured + " -> " + Math.min(peek, measured) + " (Browse " + row.getBottom()
                        + ", next " + next + ", bar " + bar + ")");
            }
            return Math.min(peek, measured);
        } catch (Throwable t) {
            android.util.Log.w("UA", "media panel peek", t);
        }
        return measured;
    }

    /** The screen top of the highest shown view in {@code v} that starts at or below {@code below}, outside {@code card}. */
    private static int nextBelow(View v, View card, int below) {
        if (v == card || v.getVisibility() != View.VISIBLE || v.getHeight() <= 0) return Integer.MAX_VALUE;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (at[1] >= below) return at[1];
        int best = Integer.MAX_VALUE;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) best = Math.min(best, nextBelow(g.getChildAt(i), card, below));
        }
        return best;
    }

    /** string/MEDIA_BROWSE: the card's Browse row. */
    private static final int BROWSE = Res.string_MEDIA_BROWSE;
    private static String browse;

    private static boolean hasText(View v, String text) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            return t != null && text.contentEquals(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
