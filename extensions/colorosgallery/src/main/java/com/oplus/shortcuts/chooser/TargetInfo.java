package com.oplus.shortcuts.chooser;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public interface TargetInfo {
    android.content.ComponentName getChooserTargetComponentName();
    android.content.pm.ShortcutInfo getDirectShareShortcutInfo();
    java.lang.CharSequence getDisplayLabel();
    float getModifiedScore();
    android.content.pm.ResolveInfo getResolveInfo();
    android.content.Intent getTargetIntent();
    boolean isPinned();
    boolean isSimilar(com.oplus.shortcuts.chooser.TargetInfo p0);
    public static void prepareIntentForCrossProfileLaunch(android.app.Activity p0, android.content.Intent p1, int p2) {
    }
}
