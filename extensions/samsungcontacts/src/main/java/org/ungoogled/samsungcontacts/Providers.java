package org.ungoogled.samsungcontacts;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.util.Log;

/**
 * Contacts watches providers only One UI has (Samsung's call and message logs, its IMS and profile providers); elsewhere Android
 * refuses to watch a provider that does not exist (SecurityException) and the app crashed while opening. Such a watch
 * now does nothing.
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
}
