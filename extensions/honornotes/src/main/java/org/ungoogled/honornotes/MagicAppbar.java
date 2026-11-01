package org.ungoogled.honornotes;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * MagicOS's expanded app bar (HwExpandedAppbarController: a big collapsing title above a scrolling list) for Honor Notes,
 * which its stand-in calls. Off MagicOS a plain one: the layout the patch adds (magic_expanded_appbar: a toolbar and
 * the app's title view, with the ids the app looks up as androidhnext's appbar_layout / toolbar / collapsing_toolbar),
 * drawn above the scrolling content, which is padded to start below it. It does not collapse.
 */
public final class MagicAppbar {
    private static final class State {
        java.lang.ref.WeakReference<Activity> activity;
        View root;
        View appbar;
        View content;
    }

    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private MagicAppbar() {
    }

    private static synchronized State state(Object controller) {
        State s = STATES.get(controller);
        if (s == null) {
            s = new State();
            STATES.put(controller, s);
        }
        return s;
    }

    private static String pkg(Context context) {
        return context.getPackageName();
    }

    public static void attach(Object controller, Activity activity) {
        state(controller).activity = new java.lang.ref.WeakReference<>(activity);
    }

    /** The layout the app inflates for the app bar. */
    public static int layout(Object controller) {
        java.lang.ref.WeakReference<Activity> ref = state(controller).activity;
        Activity activity = ref == null ? null : ref.get();
        return activity == null ? 0 : activity.getResources().getIdentifier("magic_expanded_appbar", "layout", pkg(activity));
    }

    private static View find(View root, String name) {
        if (root == null) return null;
        int id = root.getResources().getIdentifier(name, "id", pkg(root.getContext()));
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * The scrolling content (a layout) under the app bar, in the container the app added the app bar layout to: inflated
     * into the app bar layout's root, below the app bar, and padded to start under it.
     */
    public static void setContentView(Object controller, int layoutId, View container) {
        State s = state(controller);
        if (!(container instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) container;
        View root = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (find(child, "magic_appbar_layout") != null) {
                root = child;
                break;
            }
        }
        if (!(root instanceof ViewGroup) || layoutId == 0) return;
        s.root = root;
        s.appbar = find(root, "magic_appbar_layout");
        View content = LayoutInflater.from(container.getContext()).inflate(layoutId, (ViewGroup) root, false);
        ((ViewGroup) root).addView(content, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        s.content = content;
        final int basePadding = content.getPaddingTop();
        // The big title below the toolbar names the screen; the toolbar's own title (it would sit under the app's
        // drawer button) is MagicOS's collapsed title, which this app bar never shows: kept transparent (setActionBar
        // resets its text appearance).
        View toolbar = find(root, "magic_toolbar");
        s.appbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int top = v.getHeight() + basePadding;
            if (content.getPaddingTop() != top) {
                content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), content.getPaddingBottom());
            }
            if (toolbar instanceof Toolbar) ((Toolbar) toolbar).setTitleTextColor(0);
        });
    }

    /** The app's title view, under the toolbar. */
    public static void setCustomView(Object controller, View view) {
        State s = state(controller);
        View holder = find(s.root, "magic_collapsing_toolbar");
        if (!(holder instanceof ViewGroup) || view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        ((ViewGroup) holder).removeAllViews();
        ((ViewGroup) holder).addView(view);
    }

    public static View appbar(Object controller) {
        return state(controller).appbar;
    }

    public static int visibleHeight(Object controller) {
        View appbar = state(controller).appbar;
        return appbar == null ? 0 : appbar.getHeight();
    }
}
