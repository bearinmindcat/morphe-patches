package org.ungoogled.samsungemail;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.util.Log;

/**
 * Content providers only One UI has. Email watches Knox's policy provider (com.sec.knox.provider2) for changes; since
 * Android 14 watching a provider that doesn't exist throws, and off One UI that crashed Email's service at start.
 */
public final class Providers {
    private Providers() {
    }

    /** ContentResolver.registerContentObserver, skipped where the provider is missing (nothing will ever change). */
    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            Log.w("SamsungEmailPort", "not watching " + uri + ": " + e.getMessage());
        }
    }
}
