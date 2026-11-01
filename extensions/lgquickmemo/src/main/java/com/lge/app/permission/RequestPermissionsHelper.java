package com.lge.app.permission;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off LG's ROM (no LG code). */
public class RequestPermissionsHelper {
    public static boolean requestPermissionsIfNeeded(android.app.Activity p0, java.lang.String[] p1, com.lge.app.permission.GuideUiProvider p2) {
        
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String p : p1) if (p0.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) return false;
        p0.requestPermissions(need.toArray(new String[0]), 10000);
        return true;
    }
}
