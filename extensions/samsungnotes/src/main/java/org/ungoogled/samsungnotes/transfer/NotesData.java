package org.ungoogled.samsungnotes.transfer;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notes' note list, read straight from its database (databases/sdoc.db, tables sdoc and category_tree) over a separate
 * read-only connection; nothing is written here.
 */
final class NotesData {
    static final class Note {
        String uuid;
        String title;
        String path;
        long created;
        long modified;
        /** Folder names from the top ("Work", "Projects"); empty for Uncategorized. */
        List<String> folder = Collections.emptyList();
        boolean locked;
    }

    private NotesData() {
    }

    private static SQLiteDatabase open(Context context) {
        File db = context.getDatabasePath("sdoc.db");
        if (!db.exists()) return null;
        return SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    /** Folder UUID -> its names from the top; the root folders (uncategorized:///, trash:///, old:///) end the walk. */
    private static Map<String, List<String>> folders(SQLiteDatabase db) {
        Map<String, String[]> rows = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT UUID, parentUUID, displayName FROM category_tree WHERE isDeleted = 0", null)) {
            while (c.moveToNext()) rows.put(c.getString(0), new String[]{c.getString(1), c.getString(2)});
        }
        Map<String, List<String>> paths = new HashMap<>();
        for (String uuid : rows.keySet()) {
            List<String> names = new ArrayList<>();
            String at = uuid;
            for (int depth = 0; at != null && depth < 32 && !root(at); depth++) {
                String[] row = rows.get(at);
                if (row == null) break;
                names.add(0, row[1]);
                at = row[0];
            }
            paths.put(uuid, names);
        }
        return paths;
    }

    private static boolean root(String uuid) {
        return "uncategorized:///".equals(uuid) || "trash:///".equals(uuid) || "old:///".equals(uuid);
    }

    /** Every note that is not in Trash, newest first. */
    static List<Note> notes(Context context) {
        List<Note> notes = new ArrayList<>();
        SQLiteDatabase db = open(context);
        if (db == null) return notes;
        try {
            Map<String, List<String>> folders = folders(db);
            try (Cursor c = db.rawQuery("SELECT UUID, title, filePath, createdAt, lastModifiedAt, categoryUUID, isLock"
                + " FROM sdoc WHERE isDeleted = 0 ORDER BY lastModifiedAt DESC", null)) {
                while (c.moveToNext()) {
                    Note n = new Note();
                    n.uuid = c.getString(0);
                    n.title = c.getString(1);
                    n.path = c.getString(2);
                    n.created = c.getLong(3);
                    n.modified = c.getLong(4);
                    List<String> folder = folders.get(c.getString(5));
                    if (folder != null) n.folder = folder;
                    n.locked = c.getInt(6) != 0;
                    notes.add(n);
                }
            }
        } finally {
            db.close();
        }
        return notes;
    }

    /** The files of the notes (not in Trash) with these creation and change times. */
    static List<String> withTimes(Context context, long created, long modified) {
        List<String> paths = new ArrayList<>();
        SQLiteDatabase db = open(context);
        if (db == null) return paths;
        try (Cursor c = db.rawQuery("SELECT filePath FROM sdoc WHERE isDeleted = 0 AND createdAt = ? AND lastModifiedAt = ?",
            new String[]{String.valueOf(created), String.valueOf(modified)})) {
            while (c.moveToNext()) paths.add(c.getString(0));
        } finally {
            db.close();
        }
        return paths;
    }

    /** How many notes (not in Trash) and folders there are. */
    static int[] counts(Context context) {
        SQLiteDatabase db = open(context);
        if (db == null) return new int[]{0, 0};
        try {
            return new int[]{count(db, "SELECT COUNT(*) FROM sdoc WHERE isDeleted = 0"),
                count(db, "SELECT COUNT(*) FROM category_tree WHERE isDeleted = 0")};
        } finally {
            db.close();
        }
    }

    private static int count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
