package org.ungoogled.coloroslauncher;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

/**
 * Android calls a system launcher makes that an installed app may make too, but that fail for it: the patch sends them
 * here.
 */
public final class Calls {
    private static final String TAG = "UA";

    private Calls() {
    }

    /**
     * A content observer on a provider that does not exist (ColorOS's feature and settings providers): Android 14+
     * throws a SecurityException at an app that watches one; off ColorOS there is nothing to watch, so it is skipped.
     */
    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "no provider to observe: " + uri);
        }
    }

    /** ActivityManager.getCurrentUser() needs INTERACT_ACROSS_USERS: the app's own user is the one in front. */
    public static int getCurrentUser() {
        return Process.myUserHandle().hashCode();
    }
}
