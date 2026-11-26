package org.ungoogled.samsungclone;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The renamed copy's calls into other apps' content providers. On a Galaxy phone Samsung's own providers (Samsung
 * account, cloud, Knox, the system copy of a sibling app, ...) are there, but many answer only Samsung's signature
 * (SecurityException) where the port, written for phones without them, expects nothing at all. A Samsung provider
 * that refuses this copy answers as an absent one: no cursor, no result, nothing changed, no notifications. Any other
 * provider (Android's, the app's own) behaves as before. The patch sends the app's ContentResolver calls here.
 */
public final class Resolver {
    private static final String TAG = "UA";
    private static final Set<String> REFUSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Resolver() {
    }

    private static boolean samsung(String authority) {
        if (authority == null) return false;
        return authority.startsWith("com.samsung.") || authority.startsWith("com.sec.")
                || authority.startsWith("samsung.") || authority.startsWith("sec.");
    }

    /** Whether [e] is a Samsung provider refusing this copy: then the call answers as if it were absent. */
    private static boolean refused(String authority, RuntimeException e) {
        if (!(e instanceof SecurityException) || !samsung(authority)) return false;
        if (REFUSED.add(authority)) Log.w(TAG, "Samsung provider refused: " + authority + ": " + e.getMessage());
        return true;
    }

    private static String authority(Uri uri) {
        return uri != null ? uri.getAuthority() : null;
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            return r.query(uri, projection, selection, args, order);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String order,
            CancellationSignal signal) {
        try {
            return r.query(uri, projection, selection, args, order, signal);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        try {
            return r.query(uri, projection, queryArgs, signal);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static Bundle call(ContentResolver r, Uri uri, String method, String arg, Bundle extras) {
        try {
            return r.call(uri, method, arg, extras);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static Bundle call(ContentResolver r, String authority, String method, String arg, Bundle extras) {
        try {
            return r.call(authority, method, arg, extras);
        } catch (RuntimeException e) {
            if (refused(authority, e)) return null;
            throw e;
        }
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        try {
            return r.insert(uri, values);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static int update(ContentResolver r, Uri uri, ContentValues values, String where, String[] args) {
        try {
            return r.update(uri, values, where, args);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return 0;
            throw e;
        }
    }

    public static int delete(ContentResolver r, Uri uri, String where, String[] args) {
        try {
            return r.delete(uri, where, args);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return 0;
            throw e;
        }
    }

    public static String getType(ContentResolver r, Uri uri) {
        try {
            return r.getType(uri);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static void registerContentObserver(ContentResolver r, Uri uri, boolean descendants, ContentObserver observer) {
        try {
            r.registerContentObserver(uri, descendants, observer);
        } catch (RuntimeException e) {
            if (!refused(authority(uri), e)) throw e;
        }
    }

    public static void notifyChange(ContentResolver r, Uri uri, ContentObserver observer) {
        try {
            r.notifyChange(uri, observer);
        } catch (RuntimeException e) {
            if (!refused(authority(uri), e)) throw e;
        }
    }

    public static InputStream openInputStream(ContentResolver r, Uri uri) throws FileNotFoundException {
        try {
            return r.openInputStream(uri);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) throw new FileNotFoundException("refused: " + uri);
            throw e;
        }
    }

    public static ContentProviderClient acquireContentProviderClient(ContentResolver r, String authority) {
        try {
            return r.acquireContentProviderClient(authority);
        } catch (RuntimeException e) {
            if (refused(authority, e)) return null;
            throw e;
        }
    }

    public static ContentProviderClient acquireContentProviderClient(ContentResolver r, Uri uri) {
        try {
            return r.acquireContentProviderClient(uri);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }

    public static ContentProviderClient acquireUnstableContentProviderClient(ContentResolver r, String authority) {
        try {
            return r.acquireUnstableContentProviderClient(authority);
        } catch (RuntimeException e) {
            if (refused(authority, e)) return null;
            throw e;
        }
    }

    public static ContentProviderClient acquireUnstableContentProviderClient(ContentResolver r, Uri uri) {
        try {
            return r.acquireUnstableContentProviderClient(uri);
        } catch (RuntimeException e) {
            if (refused(authority(uri), e)) return null;
            throw e;
        }
    }
}
