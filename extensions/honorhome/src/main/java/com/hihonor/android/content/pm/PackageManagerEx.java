package com.hihonor.android.content.pm;

/**
 * Stand-in (written by hand): MagicOS's per-user package lookups, answered by Android's public calls for the app's own
 * user (the only one an installed app may ask about).
 */
public class PackageManagerEx {
    public static android.content.pm.ApplicationInfo getApplicationInfoAsUser(android.content.pm.PackageManager p0, java.lang.String p1, int p2, int p3) {
        try {
            return p0.getApplicationInfo(p1, p2);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
    public static boolean getBlockUninstallForUser(java.lang.String p0, int p1) {
        return false;
    }
    public static android.content.pm.PackageInfo getPackageInfoAsUser(android.content.pm.PackageManager p0, java.lang.String p1, int p2, int p3) {
        try {
            return p0.getPackageInfo(p1, p2);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
    public static boolean isUpgrade() {
        return false;
    }
    public static java.util.List queryIntentActivitiesAsUser(android.content.pm.PackageManager p0, android.content.Intent p1, int p2, int p3) {
        try {
            return p0.queryIntentActivities(p1, p2);
        } catch (RuntimeException e) {
            return new java.util.ArrayList<>();
        }
    }
    public static android.content.pm.ResolveInfo resolveActivityAsUser(android.content.pm.PackageManager p0, android.content.Intent p1, int p2, int p3) {
        try {
            return p0.resolveActivity(p1, p2);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
