package org.ungoogled.coloroslauncher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;

import java.util.List;

/**
 * Uninstalling an app. ColorOS's launcher removes apps itself (PackageManager.deletePackageAsUser), which needs
 * DELETE_PACKAGES, a permission only system apps get: off ColorOS every uninstall failed ("Failed to delete ..."). Where
 * the launcher lacks it, each app goes to Android's own uninstall prompt instead, as with any installed launcher (the
 * home screen then drops the app when Android reports it removed).
 */
public final class Uninstall {
    private static final String TAG = "UA";

    private Uninstall() {
    }

    /** PackageDeleteManager.uninstallApp(item): true when Android's prompt was opened instead. */
    public static boolean prompt(Object item) {
        Context c = App.context();
        if (c == null || item == null || allowed(c)) return false;
        open(c, item);
        return true;
    }

    /** PackageDeleteManager.uninstallApps(items): true when Android's prompt was opened for each instead. */
    public static boolean prompts(List<?> items) {
        Context c = App.context();
        if (c == null || items == null || allowed(c)) return false;
        for (Object item : items) open(c, item);
        return true;
    }

    private static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.DELETE_PACKAGES) == PackageManager.PERMISSION_GRANTED;
    }

    private static void open(Context c, Object item) {
        try {
            Object target = item.getClass().getMethod("getTargetComponent").invoke(item);
            if (!(target instanceof ComponentName)) return;
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", ((ComponentName) target).getPackageName(), null));
            Object user = field(item, "user");
            if (user instanceof UserHandle) intent.putExtra(Intent.EXTRA_USER, (UserHandle) user);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(intent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "uninstall prompt: " + e);
        }
    }

    private static Object field(Object item, String name) {
        try {
            return item.getClass().getField(name).get(item);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
