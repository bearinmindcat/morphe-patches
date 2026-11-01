package org.ungoogled.xiaomicalendar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * On HyperOS Calendar gets its calendar permissions with the system (or through the Security app's permission
 * screen, which it opens when that app is there); elsewhere it never asks, so it could read no events and save none.
 * The home screen asks for them here when the Security app is missing, and reloads once they are granted. Granting them
 * also counts as Calendar's "necessary permissions accepted" for its local features; Xiaomi's servers are a separate
 * choice (Consent).
 */
public final class Permissions {
    /** Within the 16 bits androidx's activities allow, and none of Calendar's own request codes. */
    private static final int REQUEST = 0x5ca1;

    private Permissions() {
    }

    private static volatile Boolean securityApp;

    private static boolean hasSecurityApp(Context context) {
        Boolean known = securityApp;
        if (known != null) return known;
        boolean found = false;
        for (String action : new String[]{"miui.intent.action.SYSTEM_PERMISSION_DECLARE_NEW", "miui.intent.action.SYSTEM_PERMISSION_DECLARE"}) {
            Intent intent = new Intent(action).setPackage("com.miui.securitycenter");
            if (!context.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) found = true;
        }
        securityApp = found;
        return found;
    }

    private static String[] missing(Activity activity) {
        List<String> wanted = new ArrayList<>();
        wanted.add("android.permission.READ_CALENDAR");
        wanted.add("android.permission.WRITE_CALENDAR");
        if (Build.VERSION.SDK_INT >= 33) wanted.add("android.permission.POST_NOTIFICATIONS"); // event reminders
        List<String> out = new ArrayList<>();
        for (String permission : wanted) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) out.add(permission);
        }
        return out.toArray(new String[0]);
    }

    /** Called as the home screen starts: the permissions first, then (once) Xiaomi's servers (see Consent). */
    public static void ask(Activity activity) {
        try {
            if (hasSecurityApp(activity)) return;
            String[] missing = missing(activity);
            if (missing.length == 0) {
                Consent.ask(activity, null);
                return;
            }
            activity.requestPermissions(missing, REQUEST);
        } catch (RuntimeException e) {
            // The app then runs as before, without the permissions.
        }
    }

    /**
     * Calendar's "necessary permissions accepted" ({@code accepted} is its own answer), which only the Security app's screen
     * sets: without that app it also holds once the calendar permissions are granted.
     */
    public static boolean accepted(boolean accepted) {
        if (accepted) return true;
        try {
            Context context = Calendars.context();
            return !hasSecurityApp(context)
                    && context.checkSelfPermission("android.permission.READ_CALENDAR") == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission("android.permission.WRITE_CALENDAR") == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Called with every permission result the home screen gets. */
    public static void onResult(Activity activity, int requestCode) {
        if (requestCode != REQUEST) return;
        boolean granted = activity.checkSelfPermission("android.permission.READ_CALENDAR") == PackageManager.PERMISSION_GRANTED;
        Consent.ask(activity, granted ? activity::recreate : null);
    }
}
