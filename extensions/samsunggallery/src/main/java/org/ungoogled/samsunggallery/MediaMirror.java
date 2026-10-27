package org.ungoogled.samsunggallery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.MediaStore;
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
 * analysis (stories, people, places) find nothing rather than failing.
 */
public final class MediaMirror extends ContentProvider {
    private static final String TAG = "UA";
    private static final Pattern NO_COLUMN = Pattern.compile("no such column: ([A-Za-z0-9_]+\\.)?([A-Za-z0-9_]+)");
    private static final Pattern NO_TABLE = Pattern.compile("no such table: (?:main\\.)?([A-Za-z0-9_]+)");
    /** Samsung's statements name the provider's own indexes, which the copy does not have. */
    private static final Pattern INDEX_HINT = Pattern.compile("(?i)\\s+indexed\\s+by\\s+[A-Za-z0-9_]+");

    // Columns are NUMERIC: MediaStore keeps some numbers as text (bucket_id), Samsung's queries compare them as
    // numbers, and SQLite only converts in a column with that affinity. Paths and names stay text.
    private SQLiteDatabase db;
    private long syncedGeneration = -1;
    private String syncedVersion;

    private static volatile String authority;

    /** Where Gallery's raw SQL goes; providers start before the app, so the authority is known by then. */
    public static Uri rawQueryUri() {
        return Uri.parse("content://" + authority + "/raw_sql");
    }

    @Override
    public boolean onCreate() {
        authority = getContext().getPackageName() + ".mediamirror";
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
        }
        return db;
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
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        syncedGeneration = generation;
        syncedVersion = version;
        if (debug()) Log.d(TAG, "mirror: " + rows + " files in " + (System.currentTimeMillis() - started) + " ms");
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
        try (Cursor c = d.rawQuery("PRAGMA table_info(" + table + ")", null)) {
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
            d.execSQL("ALTER TABLE " + table + " ADD COLUMN " + c.group(2) + " NUMERIC");
            Log.i(TAG, "mirror: added column " + table + "." + c.group(2));
            return true;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if (!"raw_sql".equals(uri.getLastPathSegment()) || selection == null) return null;
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

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
