package org.ungoogled.honornotes;

import android.app.Activity;
import android.view.View;

/**
 * The note list's selection mode. Notes draws its own drawer button (FolderLayoutForPhone.EXPAND_ACTION_BUTTON_FOR_PHONE)
 * over the toolbar's start, where selection mode puts its close button; off MagicOS that close button is the
 * toolbar's navigation button (MagicToolbar), below the drawer button, so a tap on the cross opened the drawer. The
 * drawer button is hidden while the list selects and comes back after.
 */
public final class Selection {
    private static final int DRAWER_BUTTON = 655369;
    private static java.lang.ref.WeakReference<View> hidden;

    private Selection() {
    }

    private static View drawerButton(Object fragment) {
        try {
            Object activity = fragment.getClass().getMethod("getActivity").invoke(fragment);
            return activity instanceof Activity ? ((Activity) activity).findViewById(DRAWER_BUTTON) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The list's toolbar now shows selection mode. */
    public static void on(Object fragment) {
        View button = drawerButton(fragment);
        if (button == null || button.getVisibility() != View.VISIBLE) return;
        button.setVisibility(View.GONE);
        hidden = new java.lang.ref.WeakReference<>(button);
    }

    /** The list's toolbar shows its normal menu again. */
    public static void off(Object fragment) {
        View button = hidden == null ? null : hidden.get();
        hidden = null;
        if (button != null && button.getVisibility() == View.GONE) button.setVisibility(View.VISIBLE);
    }
}
