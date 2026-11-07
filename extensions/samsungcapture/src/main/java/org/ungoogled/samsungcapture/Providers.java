package org.ungoogled.samsungcapture;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

/**
 * Writes and calls to content providers in Samsung capture. Several are One UI's own (Samsung's media provider
 * "secmedia", which the editor tells about every saved picture; Bixby, Routines and settings providers): off One UI
 * they are missing and Android throws ("Unknown URL"), which crashed the editor right after it had saved; on a One UI
 * phone they are there but refuse an app that is not Samsung's (SecurityException). Such a query, write or call is now
 * skipped (null / 0), and a refused watch is not set up; Android's own providers (MediaStore, where recordings and
 * pictures are saved) answer as before.
 */
public final class Providers {
    private Providers() {
    }

    private static boolean skippable(Uri uri, RuntimeException e) {
        String authority = uri == null ? null : uri.getAuthority();
        if (MediaStore.AUTHORITY.equals(authority)) return false;
        boolean missing = e instanceof IllegalArgumentException && String.valueOf(e.getMessage()).startsWith("Unknown");
        if (!missing && !(e instanceof SecurityException)) return false;
        Log.w(Projection.TAG, "provider " + authority + " skipped: " + e.getMessage());
        return true;
    }

    public static Uri insert(ContentResolver resolver, Uri uri, ContentValues values) {
        try {
            return resolver.insert(uri, values);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return null;
            throw e;
        }
    }

    public static int update(ContentResolver resolver, Uri uri, ContentValues values, String where, String[] args) {
        try {
            return resolver.update(uri, values, where, args);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return 0;
            throw e;
        }
    }

    public static int delete(ContentResolver resolver, Uri uri, String where, String[] args) {
        try {
            return resolver.delete(uri, where, args);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return 0;
            throw e;
        }
    }

    public static Bundle call(ContentResolver resolver, Uri uri, String method, String arg, Bundle extras) {
        try {
            return resolver.call(uri, method, arg, extras);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return null;
            throw e;
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, String selection, String[] args,
                               String order) {
        try {
            return resolver.query(uri, projection, selection, args, order);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return null;
            throw e;
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, Bundle queryArgs,
                               CancellationSignal signal) {
        try {
            return resolver.query(uri, projection, queryArgs, signal);
        } catch (IllegalArgumentException | SecurityException e) {
            if (skippable(uri, e)) return null;
            throw e;
        }
    }

    /** Since Android 14 watching a provider the app may not read (or that is missing) throws. */
    public static void registerContentObserver(ContentResolver resolver, Uri uri, boolean descendants,
                                               ContentObserver observer) {
        try {
            resolver.registerContentObserver(uri, descendants, observer);
        } catch (SecurityException e) {
            Log.w(Projection.TAG, "provider " + (uri == null ? null : uri.getAuthority()) + " not watched: " + e.getMessage());
        }
    }
}
