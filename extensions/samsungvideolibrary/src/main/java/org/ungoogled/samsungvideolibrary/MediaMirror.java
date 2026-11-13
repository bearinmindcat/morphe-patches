package org.ungoogled.samsungvideolibrary;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Video Library asks MediaStore in the shape of Samsung's MediaProvider: Samsung's own columns (resume position,
 * last played, hidden folders, recording type ...), GROUP BY for its folder list and Samsung's natural-order
 * collation. Google's MediaProvider refuses the whole query ("Invalid column resumePos") and the list crashed. Every
 * MediaStore question the app asks comes through here first as it was; one MediaStore refuses is answered from a copy
 * of the phone's videos in Samsung's shape, refreshed from MediaStore whenever it changes. What the app writes to
 * Samsung's columns (resume position, last played, hidden folders) is kept in a database of its own, by the video's
 * path. On a Samsung phone MediaStore answers everything and the copy is never made.
 */
public final class MediaMirror {
    private static final String TAG = "UA";
    private static final String SAVED = "saved";
    private static final String SAVED_FILE = "video_library_saved.db";
    private static final Pattern NO_COLUMN = Pattern.compile("no such column: (?:[A-Za-z0-9_]+\\.)?([A-Za-z0-9_]+)");
    /** Samsung's MediaProvider registers a natural-order collation that other phones' SQLite does not have. */
    private static final Pattern SAMSUNG_COLLATION = Pattern.compile("(?i)COLLATE\\s+UNICODE_NATURAL");
    /** Samsung's columns the list reads, with what a video that Samsung never analysed has. */
    private static final String[][] SAMSUNG_COLUMNS = {
            {"media_id", null}, {"resumePos", "0"}, {"isPlayed", "0"}, {"is_hide", null}, {"is_360_video", "0"},
            {"recording_mode", "0"}, {"recordingtype", "0"}, {"is_hdr10_video", "0"}, {"sef_file_type", "0"},
            {"group_type", "0"},
    };

    private static MediaMirror instance;

    private final Context context;
    private SQLiteDatabase db;
    private String syncedStamp;
    /** MediaStore's own columns, as the last copy saw them. */
    private final Set<String> androidColumns = new HashSet<>();

    private MediaMirror(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private static synchronized MediaMirror get(Context context) {
        if (instance == null) instance = new MediaMirror(context);
        return instance;
    }

    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    static boolean isMediaStore(Uri uri) {
        return uri != null && MediaStore.AUTHORITY.equals(uri.getAuthority());
    }

    // ---- what the app calls --------------------------------------------------------------------------------------

    static Cursor query(Context context, Uri uri, String[] projection, String selection, String[] args, String sort,
                        String groupBy, String limit, RuntimeException refused) {
        MediaMirror mirror = get(context);
        synchronized (mirror) {
            try {
                return mirror.select(uri, projection, selection, args, sort, groupBy, limit);
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaStore refused " + uri + " (" + refused.getMessage() + "), copy too: " + e.getMessage());
                return null;
            }
        }
    }

    static Cursor query(Context context, Uri uri, String[] projection, Bundle queryArgs, RuntimeException refused) {
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
        String groupBy = a.getString(ContentResolver.QUERY_ARG_SQL_GROUP_BY);
        String having = a.getString(ContentResolver.QUERY_ARG_SQL_HAVING);
        if (groupBy != null && having != null) groupBy += " HAVING " + having;
        return query(context, uri, projection, a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), sort, groupBy, limit, refused);
    }

    /** A write MediaStore refused (Samsung's columns): made in the copy, and Samsung's columns kept. */
    static int update(Context context, Uri uri, ContentValues values, String selection, String[] args,
                      RuntimeException refused) {
        MediaMirror mirror = get(context);
        int changed;
        synchronized (mirror) {
            try {
                changed = mirror.write(uri, values, selection, args);
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaStore refused an update of " + uri + " (" + refused.getMessage() + "), copy too: " + e.getMessage());
                return 0;
            }
        }
        // The list's loaders watch MediaStore's video URI.
        try {
            context.getContentResolver().notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "copy changed, lists not told: " + e.getMessage());
        }
        return changed;
    }

    private static volatile Set<String> mediaStoreColumns;

    /** The video columns this phone's MediaStore has (Samsung's MediaProvider has its own among them). */
    static Set<String> mediaStoreColumns(Context context) {
        Set<String> known = mediaStoreColumns;
        if (known != null) return known;
        known = new HashSet<>();
        try (Cursor c = context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, "0",
                null, null)) {
            if (c != null) for (String n : c.getColumnNames()) known.add(n);
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaStore columns not read: " + e.getMessage());
        }
        mediaStoreColumns = known;
        return known;
    }

    // ---- the copy ------------------------------------------------------------------------------------------------

    private SQLiteDatabase db() {
        if (db == null) {
            context.deleteDatabase("video_mirror.db");
            db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("video_mirror.db"), null);
            db.execSQL("CREATE TABLE video (_id INTEGER PRIMARY KEY)");
            String path = context.getDatabasePath(SAVED_FILE).getPath().replace("'", "''");
            db.execSQL("ATTACH DATABASE '" + path + "' AS " + SAVED);
            // name = one of Samsung's columns, value = what the app wrote for the video at that path.
            db.execSQL("CREATE TABLE IF NOT EXISTS " + SAVED + ".state (_data TEXT NOT NULL, name TEXT NOT NULL, "
                    + "value, PRIMARY KEY (_data, name))");
        }
        return db;
    }

    private static Set<String> columns(SQLiteDatabase d, String table) {
        Set<String> names = new HashSet<>();
        try (Cursor c = d.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) names.add(c.getString(1));
        }
        return names;
    }

    /** MediaStore's state, per external volume: a change in any of them means the copy is out of date. */
    private String stamp() {
        StringBuilder s = new StringBuilder(MediaStore.getVersion(context));
        for (String volume : MediaStore.getExternalVolumeNames(context)) {
            s.append(' ').append(volume).append('=').append(MediaStore.getGeneration(context, volume));
        }
        return s.toString();
    }

    /** Copies MediaStore's videos when they changed since the last copy. */
    private void sync() {
        String stamp = stamp();
        if (stamp.equals(syncedStamp)) return;
        SQLiteDatabase d = db();
        long started = System.currentTimeMillis();
        int rows = 0;
        try (Cursor src = context.getContentResolver().query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), null, null, null, null)) {
            if (src == null) return;
            String[] names = src.getColumnNames();
            androidColumns.clear();
            for (String n : names) androidColumns.add(n);
            d.beginTransaction();
            try {
                Set<String> have = columns(d, "video");
                for (String n : names) {
                    if (have.add(n)) d.execSQL("ALTER TABLE video ADD COLUMN " + n + " NUMERIC");
                }
                for (String[] samsung : SAMSUNG_COLUMNS) {
                    if (have.add(samsung[0])) d.execSQL("ALTER TABLE video ADD COLUMN " + samsung[0] + " NUMERIC");
                }
                d.execSQL("DELETE FROM video");
                ContentValues v = new ContentValues();
                while (src.moveToNext()) {
                    v.clear();
                    for (int i = 0; i < names.length; i++) {
                        switch (src.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: v.put(names[i], src.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: v.put(names[i], src.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: v.put(names[i], src.getString(i)); break;
                            case Cursor.FIELD_TYPE_BLOB: v.put(names[i], src.getBlob(i)); break;
                            default: v.putNull(names[i]);
                        }
                    }
                    d.insert("video", null, v);
                    rows++;
                }
                StringBuilder defaults = new StringBuilder("UPDATE video SET media_id = _id");
                for (String[] samsung : SAMSUNG_COLUMNS) {
                    if (samsung[1] != null) defaults.append(", ").append(samsung[0]).append(" = ").append(samsung[1]);
                }
                d.execSQL(defaults.toString());
                restoreSaved(d, have);
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        syncedStamp = stamp;
        if (debug()) Log.d(TAG, "video copy: " + rows + " videos in " + (System.currentTimeMillis() - started) + " ms");
    }

    /** What the app wrote to Samsung's columns, put back on the videos at the same paths. */
    private static void restoreSaved(SQLiteDatabase d, Set<String> have) {
        List<String> names = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT DISTINCT name FROM " + SAVED + ".state", null)) {
            while (c.moveToNext()) names.add(c.getString(0));
        }
        for (String name : names) {
            if (!name.matches("[A-Za-z0-9_]+")) continue;
            if (have.add(name)) d.execSQL("ALTER TABLE video ADD COLUMN " + name + " NUMERIC");
            d.execSQL("UPDATE video SET " + name + " = (SELECT value FROM " + SAVED + ".state s WHERE s._data = video._data "
                    + "AND s.name = ?) WHERE _data IN (SELECT _data FROM " + SAVED + ".state WHERE name = ?)", new Object[]{name, name});
        }
    }

    /** The rows a MediaStore URI names: its video ID, or every video (the copy holds videos only). */
    private static List<String> uriFilter(Uri uri) {
        List<String> path = uri.getPathSegments();
        List<String> where = new ArrayList<>();
        int idAt;
        if (path.size() >= 2 && "file".equals(path.get(1))) {
            idAt = 2;
        } else if (path.size() >= 3 && "video".equals(path.get(1)) && "media".equals(path.get(2))) {
            idAt = 3;
        } else {
            throw new IllegalArgumentException("not a video URI");
        }
        if (path.size() > idAt) where.add("_id = " + Long.parseLong(path.get(idAt)));
        return where;
    }

    private static String samsungSql(String sql) {
        return sql == null ? null : SAMSUNG_COLLATION.matcher(sql).replaceAll("COLLATE LOCALIZED");
    }

    private Cursor select(Uri uri, String[] projection, String selection, String[] args, String sort, String groupBy,
                          String limit) {
        sync();
        List<String> where = uriFilter(uri);
        if (!TextUtils.isEmpty(selection)) where.add("(" + samsungSql(selection) + ")");
        if (limit == null) limit = uri.getQueryParameter("limit");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(projection == null || projection.length == 0 ? "*" : TextUtils.join(", ", projection))
                .append(" FROM video");
        if (!where.isEmpty()) sql.append(" WHERE ").append(TextUtils.join(" AND ", where));
        if (!TextUtils.isEmpty(groupBy)) sql.append(" GROUP BY ").append(groupBy);
        if (!TextUtils.isEmpty(sort)) sql.append(" ORDER BY ").append(samsungSql(sort));
        if (!TextUtils.isEmpty(limit)) sql.append(" LIMIT ").append(limit);
        Cursor cursor = run(() -> db().rawQuery(sql.toString(), args));
        // The app's loaders reload on MediaStore's change notifications, which also renew the copy.
        cursor.setNotificationUri(context.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        if (debug()) Log.d(TAG, "video copy answered " + cursor.getCount() + " rows for " + sql);
        return cursor;
    }

    private int write(Uri uri, ContentValues values, String selection, String[] args) {
        sync();
        List<String> where = uriFilter(uri);
        if (!TextUtils.isEmpty(selection)) where.add("(" + samsungSql(selection) + ")");
        String filter = where.isEmpty() ? null : TextUtils.join(" AND ", where);
        SQLiteDatabase d = db();
        int changed = run(() -> d.update("video", values, filter, args));
        // Keep what was written, by path; MediaStore's own columns come from MediaStore on the next copy.
        for (Map.Entry<String, Object> e : values.valueSet()) {
            String name = e.getKey();
            if (androidColumns.contains(name) || !name.matches("[A-Za-z0-9_]+")) continue;
            String rows = "SELECT _data, ?, " + name + " FROM video" + (filter == null ? "" : " WHERE " + filter);
            Object[] bind = new Object[1 + (args == null ? 0 : args.length)];
            bind[0] = name;
            if (args != null) System.arraycopy(args, 0, bind, 1, args.length);
            d.execSQL("INSERT OR REPLACE INTO " + SAVED + ".state (_data, name, value) " + rows, bind);
        }
        if (debug()) Log.d(TAG, "video copy: " + changed + " rows updated with " + values.keySet());
        return changed;
    }

    private interface Statement<T> {
        T run();
    }

    /** Runs a statement; a column the copy does not have yet is added (empty) and the statement tried again. */
    private <T> T run(Statement<T> statement) {
        for (int attempt = 0; ; attempt++) {
            try {
                return statement.run();
            } catch (SQLiteException e) {
                Matcher m = NO_COLUMN.matcher(String.valueOf(e.getMessage()));
                if (attempt >= 8 || !m.find()) throw e;
                db().execSQL("ALTER TABLE video ADD COLUMN " + m.group(1) + " NUMERIC");
                Log.i(TAG, "video copy: column " + m.group(1) + " added");
            }
        }
    }
}
