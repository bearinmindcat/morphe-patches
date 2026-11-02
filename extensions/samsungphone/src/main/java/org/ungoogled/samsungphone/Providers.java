package org.ungoogled.samsungphone;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.util.Log;

/**
 * Samsung Phone watches and notifies providers only One UI has (Samsung's call and message logs, its call settings,
 * IMS and profile providers); elsewhere Android refuses to watch or notify a provider that does not exist
 * (SecurityException) and the app crashed (while opening; when blocking a number). Such a watch or notification now
 * does nothing.
 */
public final class Providers {
    private Providers() {
    }

    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            Log.w("UA", "no provider to watch: " + uri);
        }
    }

    public static void notifyChange(ContentResolver resolver, Uri uri, ContentObserver observer) {
        try {
            resolver.notifyChange(uri, observer);
        } catch (SecurityException e) {
            Log.w("UA", "no provider to notify: " + uri);
        }
    }

    public static void notifyChange(ContentResolver resolver, Uri uri, ContentObserver observer, boolean sync) {
        try {
            resolver.notifyChange(uri, observer, sync);
        } catch (SecurityException e) {
            Log.w("UA", "no provider to notify: " + uri);
        }
    }

    public static void notifyChange(ContentResolver resolver, Uri uri, ContentObserver observer, int flags) {
        try {
            resolver.notifyChange(uri, observer, flags);
        } catch (SecurityException e) {
            Log.w("UA", "no provider to notify: " + uri);
        }
    }
}
