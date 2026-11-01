package org.ungoogled.samsunggallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Gallery also asks Android's MediaStore directly, with Samsung's columns in the projection or the selection
 * (media_id, sec_media columns, cloud hashes ...). Samsung's MediaProvider has them; Google's refuses the whole query
 * ("Invalid token media_id", "Invalid column ..."), which Gallery does not expect: opening a photo from the timeline
 * crashed. Every query Gallery makes comes through here; one MediaStore refuses is answered from the media copy
 * (MediaMirror), which has Android's columns and Samsung's, or as nothing found where the copy has no such table.
 */
public final class MediaQueries {
    private static final String TAG = "UA";

    private MediaQueries() {
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, String selection,
                               String[] args, String sortOrder) {
        try {
            return resolver.query(uri, projection, selection, args, sortOrder);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!isMediaStore(uri)) throw e;
            return fromMirror(resolver, uri, projection, selection, args, sortOrder, null, e);
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, String selection,
                               String[] args, String sortOrder, CancellationSignal signal) {
        try {
            return resolver.query(uri, projection, selection, args, sortOrder, signal);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!isMediaStore(uri)) throw e;
            return fromMirror(resolver, uri, projection, selection, args, sortOrder, null, e);
        }
    }

    public static Cursor query(ContentResolver resolver, Uri uri, String[] projection, Bundle queryArgs,
                               CancellationSignal signal) {
        try {
            return resolver.query(uri, projection, queryArgs, signal);
        } catch (IllegalArgumentException | UnsupportedOperationException | SQLiteException e) {
            if (!isMediaStore(uri)) throw e;
            Bundle a = queryArgs != null ? queryArgs : Bundle.EMPTY;
            String sort = a.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
            String[] columns = a.getStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS);
            if (sort == null && columns != null && columns.length > 0) {
                boolean descending = a.getInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_ASCENDING) == ContentResolver.QUERY_SORT_DIRECTION_DESCENDING;
                sort = TextUtils.join(descending ? " DESC, " : ", ", columns) + (descending ? " DESC" : "");
            }
            String limit = a.getString(ContentResolver.QUERY_ARG_SQL_LIMIT);
            if (limit == null && a.containsKey(ContentResolver.QUERY_ARG_LIMIT)) {
                limit = a.getInt(ContentResolver.QUERY_ARG_LIMIT)
                        + (a.containsKey(ContentResolver.QUERY_ARG_OFFSET) ? " OFFSET " + a.getInt(ContentResolver.QUERY_ARG_OFFSET) : "");
            }
            return fromMirror(resolver, uri, projection, a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                    a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), sort, limit, e);
        }
    }

    private static boolean isMediaStore(Uri uri) {
        return uri != null && "media".equals(uri.getAuthority());
    }

    /**
     * The same question put to the copy's files table: content://media/VOLUME/file[/ID], .../images/media[/ID] and
     * .../video/media[/ID] (images and videos are what the copy holds). Anything else MediaStore refused finds
     * nothing.
     */
    private static Cursor fromMirror(ContentResolver resolver, Uri uri, String[] projection, String selection,
                                     String[] args, String sortOrder, String limit, RuntimeException refused) {
        List<String> path = uri.getPathSegments();
        List<String> where = new ArrayList<>();
        int idAt;
        if (path.size() >= 2 && "file".equals(path.get(1))) {
            idAt = 2;
        } else if (path.size() >= 3 && "media".equals(path.get(2)) && ("images".equals(path.get(1)) || "video".equals(path.get(1)))) {
            where.add("media_type = " + ("images".equals(path.get(1)) ? 1 : 3));
            idAt = 3;
        } else {
            Log.w(TAG, "MediaStore refused " + uri + ": " + refused.getMessage());
            return null;
        }
        if (path.size() > idAt) {
            try {
                where.add("_id = " + Long.parseLong(path.get(idAt)));
            } catch (NumberFormatException e) {
                Log.w(TAG, "MediaStore refused " + uri + ": " + refused.getMessage());
                return null;
            }
        }
        if (!TextUtils.isEmpty(selection)) where.add("(" + selection + ")");
        if (limit == null) limit = uri.getQueryParameter("limit");

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(projection == null || projection.length == 0 ? "*" : TextUtils.join(", ", projection))
                .append(" FROM files");
        if (!where.isEmpty()) sql.append(" WHERE ").append(TextUtils.join(" AND ", where));
        if (!TextUtils.isEmpty(sortOrder)) sql.append(" ORDER BY ").append(sortOrder);
        if (!TextUtils.isEmpty(limit)) sql.append(" LIMIT ").append(limit);
        try {
            Cursor cursor = resolver.query(MediaMirror.rawQueryUri(), null, sql.toString(), args, null);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "MediaStore refused (" + refused.getMessage() + "), copy answered "
                        + (cursor == null ? "nothing" : cursor.getCount() + " rows") + " for " + sql);
            }
            return cursor;
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaStore refused " + uri + " (" + refused.getMessage() + "), copy too: " + e.getMessage());
            return null;
        }
    }
}
