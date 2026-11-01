package org.ungoogled.honornotes;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notes reads, writes and watches providers of MagicOS's system services and of Honor's other apps, which are absent
 * off MagicOS: a query answers nothing, but watching one throws SecurityException ("Failed to find provider") and
 * writing to or calling one throws IllegalArgumentException. The patch sends Notes' ContentResolver calls here: an
 * Honor provider the phone lacks answers as an empty one would, everything else goes through unchanged.
 */
public final class Resolvers {
    private static final ConcurrentHashMap<String, Boolean> ABSENT = new ConcurrentHashMap<>();

    private Resolvers() {
    }

    static Context context() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** An Honor (or Huawei) provider authority nothing on this phone answers. */
    static boolean absent(String authority) {
        if (authority == null || !(authority.startsWith("com.hihonor.") || authority.startsWith("com.huawei."))) return false;
        Boolean known = ABSENT.get(authority);
        if (known != null) return known;
        Context c = context();
        boolean gone = c != null && c.getPackageManager().resolveContentProvider(authority, 0) == null;
        ABSENT.put(authority, gone);
        if (gone) Log.i("UA", "MagicOS provider absent: " + authority);
        return gone;
    }

    static boolean absent(Uri uri) {
        return uri != null && absent(uri.getAuthority());
    }

    public static void registerContentObserver(ContentResolver r, Uri uri, boolean descendants, ContentObserver observer) {
        if (absent(uri)) return;
        r.registerContentObserver(uri, descendants, observer);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort) {
        return absent(uri) ? null : r.query(uri, projection, selection, args, sort);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) {
        return absent(uri) ? null : r.query(uri, projection, selection, args, sort, signal);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        return absent(uri) ? null : r.query(uri, projection, queryArgs, signal);
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        return absent(uri) ? null : r.insert(uri, values);
    }

    public static int update(ContentResolver r, Uri uri, ContentValues values, String selection, String[] args) {
        return absent(uri) ? 0 : r.update(uri, values, selection, args);
    }

    public static int delete(ContentResolver r, Uri uri, String selection, String[] args) {
        return absent(uri) ? 0 : r.delete(uri, selection, args);
    }

    public static int bulkInsert(ContentResolver r, Uri uri, ContentValues[] values) {
        return absent(uri) ? 0 : r.bulkInsert(uri, values);
    }

    public static Bundle call(ContentResolver r, Uri uri, String method, String arg, Bundle extras) {
        return absent(uri) ? null : r.call(uri, method, arg, extras);
    }

    public static Bundle call(ContentResolver r, String authority, String method, String arg, Bundle extras) {
        return absent(authority) ? null : r.call(authority, method, arg, extras);
    }

    public static ContentProviderResult[] applyBatch(ContentResolver r, String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        return absent(authority) ? new ContentProviderResult[0] : r.applyBatch(authority, operations);
    }

    public static void notifyChange(ContentResolver r, Uri uri, ContentObserver observer) {
        if (!absent(uri)) r.notifyChange(uri, observer);
    }

    public static void notifyChange(ContentResolver r, Uri uri, ContentObserver observer, boolean syncToNetwork) {
        if (!absent(uri)) r.notifyChange(uri, observer, syncToNetwork);
    }

    public static void notifyChange(ContentResolver r, Uri uri, ContentObserver observer, int flags) {
        if (!absent(uri)) r.notifyChange(uri, observer, flags);
    }
}
