package com.lge.content.pm;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off LG's ROM (no LG code). */
public class PackageManagerEx {
    public PackageManagerEx() {
    }
    public static com.lge.content.pm.PackageManagerEx getDefault() {
        return new com.lge.content.pm.PackageManagerEx();
    }
    public java.lang.String[] getDisabledByLGLauncherPackageList(int p0) {
        return new java.lang.String[0];
    }
    public android.graphics.drawable.Drawable getIconDrawableAsIconFrameTheme(android.content.Context p0, android.graphics.drawable.Drawable p1, java.lang.String p2, int p3) {
        return p1;
    }
    public android.graphics.drawable.Drawable getIconDrawableAsTheme(android.content.Context p0, android.graphics.drawable.Drawable p1, java.lang.String p2) {
        return p1;
    }
    public android.graphics.drawable.Drawable loadUnbadgedFramedIcon(android.content.Context p0, android.content.pm.PackageItemInfo p1, android.content.pm.ApplicationInfo p2) {
        return p1 == null ? null : p1.loadUnbadgedIcon(p0.getPackageManager());
    }
}
