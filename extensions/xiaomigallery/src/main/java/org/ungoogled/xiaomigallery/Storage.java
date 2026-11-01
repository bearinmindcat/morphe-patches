package org.ungoogled.xiaomigallery;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

/**
 * Gallery needs "All files access" (on HyperOS it has it from the start). Its permission page sends a Global build to
 * the app's info screen, where that switch is not; this opens the switch itself while it is off.
 */
public final class Storage {
    private Storage() {
    }

    /** Replaces IntentUtil.enterGlobalStoragePermission(Context) on Gallery's permission page. */
    public static void permissionSettings(Context context) {
        Uri app = Uri.fromParts("package", context.getPackageName(), null);
        if (!Environment.isExternalStorageManager()) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app));
                return;
            } catch (ActivityNotFoundException e) {
                // Some ROMs only have the list of apps.
            }
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (ActivityNotFoundException e) {
                // Then the app's info screen.
            }
        }
        context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
    }
}
