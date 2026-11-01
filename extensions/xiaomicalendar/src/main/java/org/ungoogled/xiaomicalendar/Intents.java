package org.ungoogled.xiaomicalendar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * Calendar opens HyperOS's permission editor ("miui.intent.action.APP_PERM_EDITOR", the Security app) to let the user
 * change its permissions; elsewhere nothing answers it and Calendar crashed (ActivityNotFoundException). Such an
 * intent now opens Android's App info for Calendar, where its permissions are.
 */
public final class Intents {
    private static final String PERMISSION_EDITOR = "miui.intent.action.APP_PERM_EDITOR";

    private Intents() {
    }

    /** Called on every intent the permission screens make, right after it is made. */
    public static void fit(Intent intent) {
        if (intent == null || !PERMISSION_EDITOR.equals(intent.getAction())) return;
        try {
            Context context = Calendars.context();
            if (!context.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) return;
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
        } catch (RuntimeException e) {
            // Left as it was.
        }
    }
}
