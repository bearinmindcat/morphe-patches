package org.ungoogled.samsungcalendar;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tables only Samsung's calendar provider has (tasks, settings, holidays, the recycle bin, ...), kept in a small
 * SQLite database of the app's own. A table or column is made the first time something names it, so they start out
 * empty and keep whatever Calendar writes to them.
 */
final class SecTables {
    private static final String TAG = "UA";
    private static final Pattern NO_TABLE = Pattern.compile("no such table: (?:main\\.)?([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern NO_COLUMN = Pattern.compile("(?:no such column: |has no column named )([A-Za-z_][A-Za-z0-9_]*)");
    private static SQLiteDatabase db;

    private SecTables() {
    }

    private static synchronized SQLiteDatabase db() {
        if (db == null) {
            Context context = Calendars.context();
            db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("ungoogled_seccalendar.db"), null);
        }
        return db;
    }

    /** "TasksAccounts" for content://com.android.calendar/TasksAccounts[/id]. */
    static String table(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? "root" : segments.get(0).replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static long id(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) return -1;
        try {
            return Long.parseLong(segments.get(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String where(Uri uri, String selection) {
        long id = id(uri);
        if (id < 0) return selection;
        return TextUtils.isEmpty(selection) ? "_id=" + id : "_id=" + id + " AND (" + selection + ")";
    }

    /**
     * Makes what the error names, if it is a table or a plain column of this database; false when the error is
     * something else (a qualified column of another table, bad SQL).
     */
    private static boolean repair(SQLiteDatabase db, String table, SQLiteException e) {
        String message = String.valueOf(e.getMessage());
        Matcher m = NO_TABLE.matcher(message);
        if (m.find()) {
            db.execSQL("CREATE TABLE IF NOT EXISTS \"" + m.group(1) + "\" (_id INTEGER PRIMARY KEY AUTOINCREMENT)");
            return true;
        }
        m = NO_COLUMN.matcher(message);
        if (m.find() && !message.contains(m.group(1) + ".")) {
            try {
                db.execSQL("ALTER TABLE \"" + table + "\" ADD COLUMN \"" + m.group(1) + "\"");
                return true;
            } catch (SQLiteException again) {
                return false;
            }
        }
        return false;
    }

    private static void create(SQLiteDatabase db, String table) {
        db.execSQL("CREATE TABLE IF NOT EXISTS \"" + table + "\" (_id INTEGER PRIMARY KEY AUTOINCREMENT)");
    }

    static Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String table = table(uri);
        try {
            SQLiteDatabase db = db();
            create(db, table);
            for (int attempt = 0; attempt < 16; attempt++) {
                try {
                    return db.query("\"" + table + "\"", projection, where(uri, selection), args, null, null, sort);
                } catch (SQLiteException e) {
                    if (!repair(db, table, e)) throw e;
                }
            }
        } catch (RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Samsung calendar table query " + uri + ": " + e);
        }
        return new MatrixCursor(projection == null ? new String[]{"_id"} : projection);
    }

    static Uri insert(Uri uri, ContentValues values) {
        String table = table(uri);
        try {
            SQLiteDatabase db = db();
            create(db, table);
            for (int attempt = 0; attempt < 64; attempt++) {
                try {
                    long id = db.insertOrThrow("\"" + table + "\"", null, values == null ? new ContentValues() : values);
                    return ContentUris.withAppendedId(Uri.withAppendedPath(Calendars.BASE, table(uri)), id);
                } catch (SQLiteException e) {
                    if (!repair(db, table, e)) throw e;
                }
            }
        } catch (RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Samsung calendar table insert " + uri + ": " + e);
        }
        return null;
    }

    static int update(Uri uri, ContentValues values, String selection, String[] args) {
        String table = table(uri);
        try {
            SQLiteDatabase db = db();
            create(db, table);
            for (int attempt = 0; attempt < 64; attempt++) {
                try {
                    return db.update("\"" + table + "\"", values, where(uri, selection), args);
                } catch (SQLiteException e) {
                    if (!repair(db, table, e)) throw e;
                }
            }
        } catch (RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Samsung calendar table update " + uri + ": " + e);
        }
        return 0;
    }

    static int delete(Uri uri, String selection, String[] args) {
        String table = table(uri);
        try {
            SQLiteDatabase db = db();
            create(db, table);
            for (int attempt = 0; attempt < 16; attempt++) {
                try {
                    return db.delete("\"" + table + "\"", where(uri, selection), args);
                } catch (SQLiteException e) {
                    if (!repair(db, table, e)) throw e;
                }
            }
        } catch (RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Samsung calendar table delete " + uri + ": " + e);
        }
        return 0;
    }
}
