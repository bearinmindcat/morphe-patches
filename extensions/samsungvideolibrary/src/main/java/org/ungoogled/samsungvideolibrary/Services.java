package org.ungoogled.samsungvideolibrary;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;

import java.util.Map;
import java.util.Set;

import com.samsung.android.sepunion.SemEventDelegationManager;

/**
 * Android calls Video Library makes in ways only One UI or its old target SDK accepted. Every
 * Context.getSystemService(String) comes here: off One UI Samsung's own services are absent (null), and the one the
 * list uses without checking gets a stand-in that does nothing.
 */
public final class Services {
    private Services() {
    }

    public static Object get(Context context, String name) {
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        if ("semeventdelegator".equals(name)) return new SemEventDelegationManager();
        return null;
    }

    /**
     * From Android 14 on, an app built for it must say whether a receiver it registers at runtime takes broadcasts
     * from other apps; Video Library's receivers were registered without, which means "exported" for the SDK it was
     * built for. They stay exported (system broadcasts and the list's own share and package callbacks).
     */
    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    }

    /**
     * From Android 14 on, a mutable PendingIntent must name the app it is for. Video Library's (the share sheet's
     * "chosen" callback and One UI's package events) are its own broadcasts, so they are addressed to itself.
     */
    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        if ((flags & PendingIntent.FLAG_MUTABLE) != 0 && intent != null && intent.getComponent() == null
                && intent.getPackage() == null) {
            intent.setPackage(context.getPackageName());
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /**
     * Where Samsung Video Player is missing the list opens a video in any player by its MediaStore content URI; the
     * player gets read access to that one video, as a gallery app gives it.
     */
    public static void startActivity(Context context, Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && Intent.ACTION_VIEW.equals(intent.getAction()) && "content".equals(data.getScheme())) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        context.startActivity(intent);
    }

    /** The app, for the video copy (ContentResolver calls carry no Context). */
    private static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("no application", e);
        }
    }

    // MediaStore queries and writes: as asked; one MediaStore refuses is answered by the video copy (MediaMirror).

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, String selection,
                               String[] args, String sortOrder) {
        try {
            return resolver.query(uri, projection, selection, args, sortOrder);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!MediaMirror.isMediaStore(uri)) throw e;
            return MediaMirror.query(app(), uri, projection, selection, args, sortOrder, null, null, e);
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, String selection,
                               String[] args, String sortOrder, CancellationSignal signal) {
        try {
            return resolver.query(uri, projection, selection, args, sortOrder, signal);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!MediaMirror.isMediaStore(uri)) throw e;
            return MediaMirror.query(app(), uri, projection, selection, args, sortOrder, null, null, e);
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, Bundle queryArgs,
                               CancellationSignal signal) {
        try {
            return resolver.query(uri, projection, queryArgs, signal);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!MediaMirror.isMediaStore(uri)) throw e;
            return MediaMirror.query(app(), uri, projection, queryArgs, e);
        }
    }

    /**
     * What the list writes to MediaStore is Samsung's columns (resume position, last played, hidden folders); Google's
     * MediaStore refuses them, or asks for write access to videos other apps made. Those go to the copy; anything else
     * still goes to MediaStore.
     */
    public static int update(ContentResolver resolver, Uri uri, ContentValues values, String selection, String[] args) {
        try {
            return resolver.update(uri, values, selection, args);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException | SecurityException e) {
            if (!MediaMirror.isMediaStore(uri) || values == null) throw e;
            Set<String> known = MediaMirror.mediaStoreColumns(app());
            ContentValues own = new ContentValues();
            ContentValues samsung = new ContentValues();
            for (Map.Entry<String, Object> v : values.valueSet()) {
                put(known.contains(v.getKey()) ? own : samsung, v.getKey(), v.getValue());
            }
            if (samsung.size() == 0) throw e;
            int changed = MediaMirror.update(app(), uri, samsung, selection, args, e);
            if (own.size() > 0) changed = Math.max(changed, resolver.update(uri, own, selection, args));
            return changed;
        }
    }

    private static void put(ContentValues to, String key, Object value) {
        if (value == null) to.putNull(key);
        else if (value instanceof Integer) to.put(key, (Integer) value);
        else if (value instanceof Long) to.put(key, (Long) value);
        else if (value instanceof Boolean) to.put(key, (Boolean) value);
        else if (value instanceof Double) to.put(key, (Double) value);
        else if (value instanceof Float) to.put(key, (Float) value);
        else if (value instanceof byte[]) to.put(key, (byte[]) value);
        else to.put(key, value.toString());
    }
}
