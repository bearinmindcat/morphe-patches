package com.hihonor.android.widget;

/**
 * Stand-in for MagicOS's ActionBarEx (hand-written where a member names HwToolbar, a class the app bundles, which
 * .github/scripts/honor-standins cannot name; the answers are its known.py ones). No Honor code: MagicOS's toolbar
 * start and end buttons are drawn by the runtime's MagicToolbar; its split toolbar is absent.
 */
public class ActionBarEx {
    public static void setDynamicSplitToolbar(android.app.ActionBar p0, boolean p1) {
    }

    public static void setStartIcon(android.app.ActionBar p0, HwToolbar p1, boolean p2, android.graphics.drawable.Drawable p3,
            android.view.View.OnClickListener p4) {
        org.ungoogled.honorweather.MagicToolbar.setStartIcon(p0, p1, p2, p3, p4);
    }

    public static void setEndIcon(android.app.ActionBar p0, HwToolbar p1, boolean p2, android.graphics.drawable.Drawable p3,
            android.view.View.OnClickListener p4) {
        org.ungoogled.honorweather.MagicToolbar.setEndIcon(p0, p1, p2, p3, p4);
    }

    public static void setEndContentDescription(HwToolbar p0, CharSequence p1) {
        org.ungoogled.honorweather.MagicToolbar.setEndContentDescription(p0, p1);
    }
}
