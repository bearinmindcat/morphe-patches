package com.hihonor.android.widget;

/**
 * Stand-in for MagicOS's ActionBarEx (hand-written: every member Honor Recorder uses names its HwToolbar, a class the
 * app bundles, which .github/scripts/honor-standins cannot name). No Honor code: the toolbar's start button is
 * Android's navigation button (see the runtime's toolbarButton); MagicOS's split toolbar and blur effects are absent.
 */
public class ActionBarEx {
    public static void setStartIcon(android.app.ActionBar actionBar, HwToolbar toolbar, boolean show,
            android.graphics.drawable.Drawable icon, android.view.View.OnClickListener listener) {
        org.ungoogled.honorrecorder.Magic.toolbarButton(toolbar, true, show, icon, listener);
    }

    public static void setDynamicSplitToolbar(HwToolbar toolbar, boolean dynamic) {
    }

    public static void setSplitBackgroundDrawable(HwToolbar toolbar, android.graphics.drawable.Drawable background) {
    }

    public static void setSplitViewLocation(android.app.ActionBar actionBar, HwToolbar toolbar, int start, int end) {
    }

    public static void setDropdownBlurEnabled(HwToolbar toolbar, boolean enabled) {
    }

    public static boolean isSpiltViewBlurEnable(HwToolbar toolbar) {
        return false;
    }
}
