package org.ungoogled.samsunggoodlock;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/**
 * Where a module comes from off One UI. Good Lock installs modules from the Galaxy Store, which other phones do not
 * have, and a module straight from Samsung would not run there anyway: it is opened on APKMirror instead, to be patched
 * with Morphe like Good Lock itself. With the Galaxy Store installed Good Lock keeps its own way.
 */
public final class Store {
    private static final String GALAXY_STORE = "com.sec.android.app.samsungapps";

    private Store() {
    }

    /** Whether the Galaxy Store is installed (Good Lock keeps its own way there). */
    static boolean galaxyStore() {
        Context context = App.context();
        return context != null && hasGalaxyStore(context);
    }

    private static boolean hasGalaxyStore(Context context) {
        try {
            context.getPackageManager().getPackageInfo(GALAXY_STORE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens [packageName] on APKMirror; false (Good Lock's own way) where the Galaxy Store is installed. */
    public static boolean open(String packageName) {
        Context context = App.context();
        if (context == null || packageName == null || hasGalaxyStore(context)) return false;
        Uri page = Uri.parse("https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=" + Uri.encode(packageName));
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(context, "Download it from APKMirror, then patch it with Morphe", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No browser to open APKMirror", Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
