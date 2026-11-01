package com.hihonor.android.widget;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Clock uses, answering "absent" off MagicOS (no Honor code). */
public class ActionBarEx {
    public static void setDynamicSplitToolbar(com.hihonor.android.widget.HwToolbar p0, boolean p1) {
    }
    public static void setEndIcon(android.app.ActionBar p0, com.hihonor.android.widget.HwToolbar p1, boolean p2, android.graphics.drawable.Drawable p3, android.view.View.OnClickListener p4) {
        org.ungoogled.honorclock.Magic.toolbarButton(p1, false, p2, p3, p4);
    }
    public static void setSplitBackgroundDrawable(com.hihonor.android.widget.HwToolbar p0, android.graphics.drawable.Drawable p1) {
    }
    public static void setStartIcon(android.app.ActionBar p0, com.hihonor.android.widget.HwToolbar p1, boolean p2, android.graphics.drawable.Drawable p3, android.view.View.OnClickListener p4) {
        org.ungoogled.honorclock.Magic.toolbarButton(p1, true, p2, p3, p4);
    }
}
