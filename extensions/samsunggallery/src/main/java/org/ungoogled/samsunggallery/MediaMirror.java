package org.ungoogled.samsunggallery;

import android.content.ContentProvider;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samsung's MediaProvider answers raw SQL over its own tables (files with Samsung's columns, and views for
 * stories, people, places ...); Google's does not. Gallery's queries are answered here instead, from a copy of
 * the phone's photos and videos in Samsung's shape: the files table, refreshed from MediaStore whenever it
 * changes. A table or column the copy does not know yet is added empty, so features built on Samsung's
 * analysis (stories, people, places) find nothing rather than failing. What the user adds (tags) is kept in a
 * database of its own that the copy attaches.
 */
public final class MediaMirror extends ContentProvider {
    private static final String TAG = "UA";
    private static final Pattern NO_COLUMN = Pattern.compile("no such column: ([A-Za-z0-9_]+\\.)?([A-Za-z0-9_]+)");
    private static final Pattern NO_TABLE = Pattern.compile("no such table: (?:main\\.)?([A-Za-z0-9_]+)");
    /** Samsung's statements name the provider's own indexes, which the copy does not have. */
    private static final Pattern INDEX_HINT = Pattern.compile("(?i)\\s+indexed\\s+by\\s+[A-Za-z0-9_]+");
    private static final String USER_TAGS = "usertag";
    private static final String TAG_VIEW = "tagview";
    private static final String SAVED = "saved";
    private static final String SAVED_FILE = "gallery_saved.db";

    // Columns are NUMERIC: MediaStore keeps some numbers as text (bucket_id), Samsung's queries compare them as
    // numbers, and SQLite only converts in a column with that affinity. Paths and names stay text.
    private SQLiteDatabase db;
    private long syncedGeneration = -1;
    private String syncedVersion;

    private static volatile String authority;
    private static volatile MediaMirror instance;

    /** Where Gallery's raw SQL goes; providers start before the app, so the authority is known by then. */
    public static Uri rawQueryUri() {
        return Uri.parse("content://" + authority + "/raw_sql");
    }

    /** Gallery's user tags (One UI keeps them in Samsung's media provider, content://secmedia/cmh/usertag). */
    public static Uri userTagsUri() {
        return Uri.parse("content://" + authority + "/" + USER_TAGS);
    }

    /** Every tag of every photo (One UI's tag view): sec_media_id, tag_data, tag_type (0 = the user's own tag). */
    public static Uri tagViewUri() {
        return Uri.parse("content://" + authority + "/" + TAG_VIEW);
    }

    /** The app's context; the provider starts with the app's process. */
    public static Context context() {
        MediaMirror mirror = instance;
        return mirror == null ? null : mirror.getContext();
    }

    /**
     * Sets favourites in the copy at once: Gallery reloads right after its heart is tapped, while Android applies the
     * change to MediaStore a moment later (the copy then follows MediaStore again).
     */
    static void markFavorite(long[] ids, boolean favorite) {
        MediaMirror mirror = instance;
        if (mirror == null || ids.length == 0) return;
        StringBuilder in = new StringBuilder();
        for (long id : ids) in.append(in.length() == 0 ? "" : ",").append(id);
        synchronized (mirror) {
            try {
                mirror.db().execSQL("UPDATE files SET is_favorite = " + (favorite ? 1 : 0) + " WHERE _id IN (" + in + ")");
            } catch (SQLiteException e) {
                Log.w(TAG, "mirror: favourite not marked: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean onCreate() {
        instance = this;
        authority = getContext().getPackageName() + ".mediamirror";
        // Providers start with the app's process, before any of its screens.
        ActivityState.track(getContext());
        return true;
    }

    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    private synchronized SQLiteDatabase db() {
        if (db == null) {
            Context c = getContext();
            c.deleteDatabase("media_mirror.db");
            db = SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath("media_mirror.db"), null);
            db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY)");
            attachSaved(db);
        }
        return db;
    }

    /**
     * What the user adds to Gallery lives in a database of its own, attached to the copy (which is rebuilt from
     * MediaStore on every start) so that Gallery's statements find it under One UI's names: the user's tags (One UI's
     * usertag table) and the tag view over them that Gallery searches.
     */
    private void attachSaved(SQLiteDatabase d) {
        boolean attached = false;
        try (Cursor c = d.rawQuery("PRAGMA database_list", null)) {
            while (c.moveToNext()) attached |= SAVED.equals(c.getString(1));
        }
        if (!attached) {
            String path = getContext().getDatabasePath(SAVED_FILE).getPath().replace("'", "''");
            d.execSQL("ATTACH DATABASE '" + path + "' AS " + SAVED);
        }
        // _data: the photo's path, so its tags find it again if MediaStore gives it a new id (its database rebuilt).
        d.execSQL("CREATE TABLE IF NOT EXISTS " + SAVED + ".usertag (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "sec_media_id INTEGER, tag TEXT, server_id TEXT, _data TEXT, UNIQUE (sec_media_id, tag) ON CONFLICT IGNORE)");
        d.execSQL("CREATE INDEX IF NOT EXISTS " + SAVED + ".usertag_media ON usertag (sec_media_id)");
        ExifOrientation.createTable(d, SAVED);
        // One UI's view puts every kind of tag together; tag_type 0 = the user's own. Gallery orders by its rowid.
        d.execSQL("CREATE TEMP VIEW IF NOT EXISTS tag_view AS SELECT _id AS rowid, _id, sec_media_id, tag AS tag_data, "
                + "0 AS tag_type FROM " + SAVED + ".usertag");
    }

    /** The saved tables are gone from the connection (SQLite reopened it): attach them again. */
    private synchronized boolean reattach(SQLiteException e) {
        String msg = String.valueOf(e.getMessage());
        if (!msg.contains("no such table: usertag") && !msg.contains("no such table: tag_view")
                && !msg.contains("no such table: " + SAVED + ".")) return false;
        attachSaved(db());
        Log.i(TAG, "mirror: saved tables attached again");
        return true;
    }

    /** Photos read as mirrored (id to EXIF orientation value), for photos that are not in the copy (in the trash). */
    static Map<Long, Integer> mirroredPhotos() {
        MediaMirror mirror = instance;
        Map<Long, Integer> mirrored = new HashMap<>();
        if (mirror == null) return mirrored;
        synchronized (mirror) {
            try (Cursor c = mirror.db().rawQuery("SELECT _id, tag FROM " + SAVED + ".exif_orientation WHERE tag IN (2, 4, 5, 7)", null)) {
                while (c.moveToNext()) mirrored.put(c.getLong(0), c.getInt(1));
            } catch (SQLiteException e) {
                Log.w(TAG, "mirror: " + e.getMessage());
            }
        }
        return mirrored;
    }

    /** The copy's database, for work that runs outside a query (lock the provider around its use). */
    synchronized SQLiteDatabase database() {
        return db();
    }

    /** Copies MediaStore's photos and videos when its contents changed since the last copy. */
    private synchronized void sync() {
        Context c = getContext();
        String version = MediaStore.getVersion(c);
        long generation = MediaStore.getGeneration(c, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        if (generation == syncedGeneration && version.equals(syncedVersion)) return;
        SQLiteDatabase d = db();
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String where = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,3)";
        long started = System.currentTimeMillis();
        int rows = 0;
        try (Cursor src = c.getContentResolver().query(files, null, where, null, null)) {
            if (src == null) return;
            Set<String> have = columns(d, "files");
            String[] names = src.getColumnNames();
            for (String n : names) {
                if (!have.contains(n)) d.execSQL("ALTER TABLE files ADD COLUMN " + n + " NUMERIC");
            }
            d.beginTransaction();
            try {
                d.execSQL("DELETE FROM files");
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
                    d.insert("files", null, v);
                    rows++;
                }
                fillSamsungColumns(d);
                if (!columns(d, "files").contains("orientation_tag")) {
                    d.execSQL("ALTER TABLE files ADD COLUMN orientation_tag NUMERIC");
                }
                ExifOrientation.fill(this, d, SAVED);
                // Gallery decides whether its lists changed from a stamp that includes One UI's
                // local_metadata.generation_modified; without the table the whole stamp was NULL, always "the same",
                // and lists did not refresh after a delete or a new photo.
                d.execSQL("CREATE TABLE IF NOT EXISTS local_metadata (_id INTEGER PRIMARY KEY)");
                if (!columns(d, "local_metadata").contains("generation_modified")) {
                    d.execSQL("ALTER TABLE local_metadata ADD COLUMN generation_modified NUMERIC");
                }
                d.execSQL("DELETE FROM local_metadata");
                d.execSQL("INSERT INTO local_metadata (generation_modified) VALUES (" + generation + ")");
                refindTags(d);
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        syncedGeneration = generation;
        syncedVersion = version;
        if (debug()) Log.d(TAG, "mirror: " + rows + " files in " + (System.currentTimeMillis() - started) + " ms");
    }

    /**
     * Tags whose photo is no longer in the copy under its id: a photo MediaStore gave a new id (its database rebuilt)
     * gets them back by its path. A photo in Android's trash keeps its id and comes back with it, so its tags stay.
     */
    private void refindTags(SQLiteDatabase d) {
        Set<Long> lost = new HashSet<>();
        try (Cursor c = d.rawQuery("SELECT DISTINCT sec_media_id FROM " + SAVED + ".usertag WHERE sec_media_id NOT IN "
                + "(SELECT _id FROM files) AND _data IN (SELECT _data FROM files)", null)) {
            while (c.moveToNext()) lost.add(c.getLong(0));
        }
        if (lost.isEmpty()) return;
        Bundle q = new Bundle();
        q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id IN (" + TextUtils.join(",", lost) + ")");
        q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor c = getContext().getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{"_id"}, q, null)) {
            while (c != null && c.moveToNext()) lost.remove(c.getLong(0));
        } catch (RuntimeException e) {
            Log.w(TAG, "mirror: tags not checked: " + e.getMessage());
            return;
        }
        if (lost.isEmpty()) return;
        d.execSQL("UPDATE " + SAVED + ".usertag SET sec_media_id = (SELECT f._id FROM files f WHERE f._data = usertag._data) "
                + "WHERE sec_media_id IN (" + TextUtils.join(",", lost) + ") AND _data IN (SELECT _data FROM files)");
        Log.i(TAG, "mirror: tags of " + lost.size() + " photos found again by path");
    }

    /** Samsung's own columns that have a meaning without Samsung's analysis. */
    private static void fillSamsungColumns(SQLiteDatabase d) {
        Set<String> have = columns(d, "files");
        Map<String, String> derived = new HashMap<>();
        derived.put("media_id", "_id");
        // 1 = on the phone (2 is only in Samsung Cloud).
        derived.put("is_cloud", "1");
        // Date taken in ms, Samsung's timeline key.
        derived.put("datetime", "COALESCE(datetaken, date_modified * 1000, date_added * 1000)");
        derived.put("is_hide", "0");
        // Not part of a burst or similar-shots group.
        derived.put("group_id", "0");
        derived.put("burst_group_id", "0");
        // A plain file, not a Samsung extended one (motion photo, edited original ...).
        derived.put("sef_file_type", "0");
        for (Map.Entry<String, String> e : derived.entrySet()) {
            if (!have.contains(e.getKey())) d.execSQL("ALTER TABLE files ADD COLUMN " + e.getKey() + " NUMERIC");
            d.execSQL("UPDATE files SET " + e.getKey() + " = " + e.getValue());
        }
    }

    private static Set<String> columns(SQLiteDatabase d, String table) {
        Set<String> set = new HashSet<>();
        int dot = table.indexOf('.');
        String pragma = dot < 0 ? "PRAGMA table_info(" + table + ")"
                : "PRAGMA " + table.substring(0, dot) + ".table_info(" + table.substring(dot + 1) + ")";
        try (Cursor c = d.rawQuery(pragma, null)) {
            while (c.moveToNext()) set.add(c.getString(1));
        }
        return set;
    }

    /** The table an alias stands for in a statement ("FROM files A", "JOIN story_view AS S"), or files. */
    private static String tableOf(String sql, String alias) {
        if (alias == null) return null;
        Matcher m = Pattern.compile("(?i)(?:FROM|JOIN)\\s+([A-Za-z0-9_]+)\\s+(?:AS\\s+)?" + Pattern.quote(alias) + "\\b").matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    private synchronized boolean heal(String sql, SQLiteException e) {
        if (reattach(e)) return true;
        String msg = String.valueOf(e.getMessage());
        SQLiteDatabase d = db();
        Matcher t = NO_TABLE.matcher(msg);
        if (t.find()) {
            d.execSQL("CREATE TABLE IF NOT EXISTS " + t.group(1) + " (_id INTEGER PRIMARY KEY)");
            Log.i(TAG, "mirror: added table " + t.group(1));
            return true;
        }
        Matcher c = NO_COLUMN.matcher(msg);
        if (c.find()) {
            String alias = c.group(1) == null ? null : c.group(1).substring(0, c.group(1).length() - 1);
            String table = tableOf(sql, alias);
            if (table == null) table = alias != null && !columns(d, alias).isEmpty() ? alias : "files";
            if (columns(d, table).isEmpty()) d.execSQL("CREATE TABLE IF NOT EXISTS " + table + " (_id INTEGER PRIMARY KEY)");
            if (columns(d, table).contains(c.group(2))) return false;
            try {
                d.execSQL("ALTER TABLE " + table + " ADD COLUMN " + c.group(2) + " NUMERIC");
            } catch (SQLiteException notTable) {
                // A view (tag_view): its columns are fixed.
                return false;
            }
            Log.i(TAG, "mirror: added column " + table + "." + c.group(2));
            return true;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String path = uri.getLastPathSegment();
        if (USER_TAGS.equals(path) || TAG_VIEW.equals(path)) {
            return savedQuery(USER_TAGS.equals(path) ? SAVED + ".usertag" : "tag_view", projection, selection, args, sortOrder);
        }
        if (!"raw_sql".equals(path) || selection == null) return null;
        selection = INDEX_HINT.matcher(selection).replaceAll(" ");
        sync();
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                Cursor cursor = db().rawQuery(selection, args);
                int count = cursor.getCount();
                if (debug()) {
                    String first = "";
                    if (count == 1 && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                        first = " = " + cursor.getString(0);
                        cursor.moveToPosition(-1);
                    }
                    Log.d(TAG, "mirror: " + count + " rows" + first + " for " + selection.replace('\n', ' '));
                }
                return cursor;
            } catch (SQLiteException e) {
                if (!heal(selection, e)) {
                    Log.w(TAG, "mirror: " + e.getMessage() + " in " + selection);
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    private Cursor savedQuery(String table, String[] projection, String selection, String[] args, String sortOrder) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return db().query(table, projection, selection, args, null, null, sortOrder);
            } catch (SQLiteException e) {
                if (attempt > 0 || !reattach(e)) {
                    Log.w(TAG, "mirror: " + e.getMessage() + " in " + table);
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean isUserTags(Uri uri) {
        return USER_TAGS.equals(uri.getLastPathSegment());
    }

    /** One tag of one photo (Gallery's tag, sec_media_id and, with Samsung Cloud, server_id). */
    private long insertTag(SQLiteDatabase d, ContentValues values) {
        ContentValues v = new ContentValues(values);
        Set<String> have = columns(d, SAVED + ".usertag");
        for (String key : v.keySet()) {
            if (!have.contains(key)) d.execSQL("ALTER TABLE " + SAVED + ".usertag ADD COLUMN " + key + " NUMERIC");
        }
        Long media = v.getAsLong("sec_media_id");
        if (media != null && !v.containsKey("_data")) {
            try (Cursor c = d.rawQuery("SELECT _data FROM files WHERE _id = " + media, null)) {
                if (c.moveToFirst()) v.put("_data", c.getString(0));
            }
        }
        return d.insert(SAVED + ".usertag", null, v);
    }

    private void changed(Uri uri) {
        getContext().getContentResolver().notifyChange(uri, null);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!isUserTags(uri) || values == null) return null;
        long id;
        sync();
        synchronized (this) {
            id = insertTag(db(), values);
        }
        changed(uri);
        return Uri.withAppendedPath(uri, String.valueOf(Math.max(id, 0)));
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        if (!isUserTags(uri) || values == null) return 0;
        int added = 0;
        sync();
        synchronized (this) {
            SQLiteDatabase d = db();
            d.beginTransaction();
            try {
                for (ContentValues v : values) {
                    if (v != null && insertTag(d, v) > 0) added++;
                }
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        if (debug()) Log.d(TAG, "mirror: " + added + " of " + values.length + " tags added");
        changed(uri);
        return values.length;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        if (!isUserTags(uri)) return 0;
        int n;
        synchronized (this) {
            n = db().delete(SAVED + ".usertag", selection, args);
        }
        if (debug()) Log.d(TAG, "mirror: " + n + " tags removed");
        if (n > 0) changed(uri);
        return n;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        if (!isUserTags(uri) || values == null) return 0;
        int n;
        synchronized (this) {
            n = db().update(SAVED + ".usertag", values, selection, args);
        }
        if (n > 0) changed(uri);
        return n;
    }
}
