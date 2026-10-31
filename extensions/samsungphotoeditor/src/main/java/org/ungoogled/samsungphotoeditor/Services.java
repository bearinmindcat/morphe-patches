package org.ungoogled.samsungphotoeditor;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/** Android calls Photo Editor makes that fail off One UI, answered the way the app can carry on from. */
public final class Services {
    private Services() {
    }

    /**
     * A native library only One UI has (or one of the app's own that links against one) cannot be loaded; the classes
     * bound to it answer nothing instead (see the patch), so the load failing is not fatal.
     */
    public static void loadLibrary(String name) {
        try {
            System.loadLibrary(name);
        } catch (UnsatisfiedLinkError e) {
            Log.w("UA", "native library not available: " + name);
        }
    }

    public static void load(String path) {
        try {
            System.load(path);
        } catch (UnsatisfiedLinkError e) {
            Log.w("UA", "native library not available: " + path);
        }
    }

    // ---- providers only One UI has --------------------------------------------------------------------------------

    private static final ConcurrentHashMap<String, Boolean> PRESENT = new ConcurrentHashMap<>();

    /**
     * Whether a provider exists for the uri. Photo Editor also writes to One UI's own media database ("secmedia")
     * and other Samsung apps' providers; where they are absent, AOSP throws "Unknown URI" and saving crashed.
     */
    private static boolean present(ContentResolver resolver, Uri uri) {
        String authority = uri != null ? uri.getAuthority() : null;
        if (authority == null) return true;
        Boolean known = PRESENT.get(authority);
        if (known != null) return known;
        boolean present;
        try (ContentProviderClient client = resolver.acquireUnstableContentProviderClient(authority)) {
            present = client != null;
        } catch (RuntimeException e) {
            present = false;
        }
        if (!present) Log.w("UA", "provider not available: " + authority);
        PRESENT.put(authority, present);
        return present;
    }

    public static int update(ContentResolver resolver, Uri uri, ContentValues values, String where, String[] args) {
        return present(resolver, uri) ? resolver.update(uri, values, where, args) : 0;
    }

    public static int update(ContentResolver resolver, Uri uri, ContentValues values, Bundle extras) {
        return present(resolver, uri) ? resolver.update(uri, values, extras) : 0;
    }

    public static Uri insert(ContentResolver resolver, Uri uri, ContentValues values) {
        return present(resolver, uri) ? resolver.insert(uri, values) : null;
    }

    public static int bulkInsert(ContentResolver resolver, Uri uri, ContentValues[] values) {
        return present(resolver, uri) ? resolver.bulkInsert(uri, values) : 0;
    }

    public static int delete(ContentResolver resolver, Uri uri, String where, String[] args) {
        return present(resolver, uri) ? resolver.delete(uri, where, args) : 0;
    }

    public static Bundle call(ContentResolver resolver, Uri uri, String method, String arg, Bundle extras) {
        return present(resolver, uri) ? resolver.call(uri, method, arg, extras) : null;
    }
}
