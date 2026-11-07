package com.lge.app.permission;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * like LG's helper, a screen that still lacks permissions is closed and a permission request takes its place, which
 * opens the screen again once they are granted (Gallery has no permission result handler of its own: it relies on
 * this). True while something is being asked.
 */
public class RequestPermissionsHelper {
    public static boolean requestPermissionsIfNeeded(android.app.Activity p0, java.lang.String[] p1, com.lge.app.permission.GuideUiProvider p2) {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String p : p1) if (p0.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) return false;
        org.ungoogled.lggallery.PermissionRequestActivity.start(p0, need.toArray(new String[0]));
        return true;
    }
}
