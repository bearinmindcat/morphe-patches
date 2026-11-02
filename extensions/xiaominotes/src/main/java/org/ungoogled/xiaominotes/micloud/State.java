package org.ungoogled.xiaominotes.micloud;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Our own record of what came from Xiaomi Cloud (a separate database, not Notes'): cloud note id -> local note id with
 * the cloud's tag and modify date and a hash of the local title + body as last written or synced, so a second import
 * only fetches new or changed notes and never overwrites a note edited on the phone. Also the cloud folder map and a
 * few settings.
 */
final class State extends SQLiteOpenHelper {
    static final class Note {
        String cloudId;
        long localId;
        String tag;
        long modify;
        String hash;
        String cloudFolder;
        /** The cloud entry as last seen, without its body: sync sends back the fields it does not change. */
        String entry;
    }

    private static State instance;

    /** One instance per process, so the screen and a sync never write through two connections. */
    static synchronized State get(Context context) {
        if (instance == null) instance = new State(context.getApplicationContext());
        return instance;
    }

    private State(Context context) {
        super(context, "ungoogled_micloud.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (cloud_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL, tag TEXT, modify INTEGER, " +
            "hash TEXT, cloud_folder TEXT, entry TEXT)");
        db.execSQL("CREATE INDEX notes_local ON notes (local_id)");
        db.execSQL("CREATE TABLE folders (cloud_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL, tag TEXT, name TEXT)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ---- notes

    Note note(String cloudId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes WHERE cloud_id = ?", new String[] {cloudId})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    Note noteByLocal(long localId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes WHERE local_id = ?",
            new String[] {String.valueOf(localId)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    List<Note> notes() {
        List<Note> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes", null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    private static Note read(Cursor c) {
        Note n = new Note();
        n.cloudId = c.getString(c.getColumnIndexOrThrow("cloud_id"));
        n.localId = c.getLong(c.getColumnIndexOrThrow("local_id"));
        n.tag = c.getString(c.getColumnIndexOrThrow("tag"));
        n.modify = c.getLong(c.getColumnIndexOrThrow("modify"));
        n.hash = c.getString(c.getColumnIndexOrThrow("hash"));
        n.cloudFolder = c.getString(c.getColumnIndexOrThrow("cloud_folder"));
        n.entry = c.getString(c.getColumnIndexOrThrow("entry"));
        return n;
    }

    void put(Note n) {
        ContentValues v = new ContentValues();
        v.put("cloud_id", n.cloudId);
        v.put("local_id", n.localId);
        v.put("tag", n.tag);
        v.put("modify", n.modify);
        v.put("hash", n.hash);
        v.put("cloud_folder", n.cloudFolder);
        v.put("entry", n.entry);
        getWritableDatabase().insertWithOnConflict("notes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void removeNote(String cloudId) {
        getWritableDatabase().delete("notes", "cloud_id = ?", new String[] {cloudId});
    }

    int noteCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notes", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Forgets what came from where (another account): notes already imported stay as they are. */
    void clearRecords() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("notes", null, null);
        db.delete("folders", null, null);
        set("syncTag", null);
        set("format", null);
    }

    // ---- folders

    long folder(String cloudId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT local_id FROM folders WHERE cloud_id = ?", new String[] {cloudId})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    String cloudFolder(long localId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT cloud_id FROM folders WHERE local_id = ?",
            new String[] {String.valueOf(localId)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void putFolder(String cloudId, long localId, String tag, String name) {
        ContentValues v = new ContentValues();
        v.put("cloud_id", cloudId);
        v.put("local_id", localId);
        v.put("tag", tag);
        v.put("name", name);
        getWritableDatabase().insertWithOnConflict("folders", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- settings

    String get(String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k = ?", new String[] {key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void set(String key, String value) {
        if (value == null) {
            getWritableDatabase().delete("meta", "k = ?", new String[] {key});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("k", key);
        v.put("v", value);
        getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    boolean flag(String key) {
        return "1".equals(get(key));
    }

    void flag(String key, boolean on) {
        set(key, on ? "1" : null);
    }

    /** Hash of what the user sees and edits: title and body. */
    static String hash(String title, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((title == null ? "" : title).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
