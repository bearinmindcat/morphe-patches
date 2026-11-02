package org.ungoogled.xiaominotes.micloud;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes' own database (Room, "ai_agentic_db"). Writes go through Notes' code: its backup helper hands out the app's
 * AppDatabase, NoteEntity / FolderEntity are built with their constructors (both keep their names) and Dao inserts
 * them the way Notes does, so the note list updates at once. Reads use a separate read-only connection.
 */
final class NotesStore implements Closeable {
    static final String DB_NAME = "ai_agentic_db";

    /**
     * NoteEntity's constructor parameters in order: the `documents` columns, as in Room's INSERT for this build. The
     * patch checks this list against the INSERT statement in the app.
     */
    static final String[] COLUMNS = {
        "id", "title", "content", "createdAt", "updatedAt", "parentId", "noteType", "tags", "audioPath", "legacyId",
        "folderId", "stickDate", "isPinned", "deletedAt", "deletionTag", "uuid", "isPrivate", "alertDate", "widgetId",
        "legacySyncId", "legacyContentSha1", "cardId", "transcriptText", "transcriptSentencesJson", "source",
        "derivedSource", "doodleEntMap", "mpfName", "thumbnail", "recognizedFiles", "entMissing", "showAiGeneratedTag",
        "callDate", "callNumber", "importState", "listPreview", "firstImageUri", "mindmapSnapshot", "themeId",
        "external_id", "podcastId", "lrcPath", "transcriptPath", "category", "durationMs", "podcastStatus",
        "podcastPlayState",
    };
    static final int ID = 0, TITLE = 1, CONTENT = 2, CREATED = 3, UPDATED = 4, PARENT = 5, TYPE = 6, TAGS = 7,
        FOLDER = 10, STICK = 11, PINNED = 12, DELETED = 13, DELETION_TAG = 14, UUID_ = 15, PRIVATE = 16, WIDGET = 18,
        ENT_MISSING = 30, AI_TAG = 31, IMPORT_STATE = 34, PREVIEW = 35, FIRST_IMAGE = 36, THEME = 38, EXTERNAL = 39,
        PODCAST_STATE = 46;

    private static final Pattern IMAGE = Pattern.compile("!\\[.*?]\\(https://com\\.miui\\.notes/image/([^?)]+)");

    private final Object handle;
    private final Object db;
    private final java.io.File file;
    private SQLiteDatabase read;
    private final Constructor<?> noteConstructor;
    private final Constructor<?> folderConstructor;

    NotesStore(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> helper = Class.forName("com.miui.notes.backup.modern.RestoreHelper", true, loader);
        Object instance = helper.getField("INSTANCE").get(null);
        handle = helper.getMethod("openRoomDb", Context.class).invoke(instance, context);
        db = handle.getClass().getMethod("getDb").invoke(handle);
        noteConstructor = constructor(Class.forName("com.miui.notes.storage.entity.NoteEntity", true, loader), COLUMNS.length);
        folderConstructor = constructor(Class.forName("com.miui.notes.storage.entity.FolderEntity", true, loader), 9);
        file = context.getDatabasePath(DB_NAME);
    }

    /** A read-only connection, once Room has created the database (a fresh install creates it on its first write). */
    private SQLiteDatabase read() {
        if (read == null && file.isFile()) read = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        return read;
    }

    private static Constructor<?> constructor(Class<?> type, int parameters) {
        for (Constructor<?> c : type.getConstructors()) {
            if (c.getParameterTypes().length == parameters) return c;
        }
        throw new IllegalStateException(type.getName() + " has no " + parameters + "-argument constructor");
    }

    @Override
    public void close() {
        try {
            if (read != null) read.close();
        } catch (Exception ignored) {
        }
        try {
            Method close = handle.getClass().getMethod("closeIfNeeded");
            close.invoke(handle);
        } catch (Exception ignored) {
        }
    }

    // ---- notes

    /** A `documents` row as NoteEntity's constructor arguments, or null. */
    Object[] note(long id) {
        if (read() == null) return null;
        try (Cursor c = read.rawQuery("SELECT * FROM documents WHERE id = ?", new String[] {String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            Class<?>[] types = noteConstructor.getParameterTypes();
            Object[] values = new Object[COLUMNS.length];
            for (int i = 0; i < COLUMNS.length; i++) {
                int column = c.getColumnIndex(COLUMNS[i]);
                values[i] = column < 0 || c.isNull(column) ? defaultFor(types[i]) : value(c, column, types[i]);
            }
            return values;
        }
    }

    private static Object value(Cursor c, int column, Class<?> type) {
        if (type == long.class || type == Long.class) return c.getLong(column);
        if (type == int.class || type == Integer.class) return c.getInt(column);
        if (type == boolean.class || type == Boolean.class) return c.getInt(column) != 0;
        return c.getString(column);
    }

    private static Object defaultFor(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }

    /** Arguments for a new top-level note (Notes' defaults, as its external-note insert uses). */
    static Object[] newNote(String title, String content, long createdAt, long updatedAt) {
        Object[] v = new Object[COLUMNS.length];
        v[ID] = 0L;
        v[TITLE] = title == null ? "" : title;
        v[CONTENT] = content == null ? "" : content;
        v[CREATED] = createdAt;
        v[UPDATED] = updatedAt;
        v[TYPE] = "main";
        v[TAGS] = "[]";
        v[STICK] = 0L;
        v[PINNED] = false;
        v[DELETION_TAG] = 0;
        v[UUID_] = UUID.randomUUID().toString();
        v[PRIVATE] = false;
        v[WIDGET] = 0;
        v[ENT_MISSING] = false;
        v[AI_TAG] = false;
        v[IMPORT_STATE] = 0;
        v[PREVIEW] = "";
        v[THEME] = 0;
        v[PODCAST_STATE] = 0;
        return v;
    }

    /** Writes the note (inserts it when its id is 0, otherwise updates that row) and returns its id. */
    long putNote(Object[] values) throws Exception {
        String content = (String) values[CONTENT];
        values[PREVIEW] = listPreview(content);
        values[FIRST_IMAGE] = firstImage(content);
        Object entity = noteConstructor.newInstance(values);
        return Dao.putNote(db, entity);
    }

    /** What Notes stores as listPreview (RestoreHelper.extractListPreview): text without media, headings unmarked. */
    static String listPreview(String content) {
        if (content == null || content.trim().isEmpty()) return "";
        String s = content.replaceAll("!\\[.*?]\\(.*?\\)", "");
        s = s.replaceAll("\\[.*?]\\(https://com\\.miui\\.notes/audio/.*?\\)", "");
        s = Pattern.compile("^#+\\s*", Pattern.MULTILINE).matcher(s).replaceAll("").trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    static String firstImage(String content) {
        if (content == null) return null;
        Matcher m = IMAGE.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /** Ids of the user's own notes, as sync considers them: top-level, not private, not a voice/AI/handwriting note. */
    List<long[]> plainNotes() {
        List<long[]> out = new ArrayList<>();
        if (read() == null) return out;
        try (Cursor c = read.rawQuery(
            "SELECT id, updatedAt, deletedAt IS NOT NULL FROM documents WHERE parentId IS NULL AND isPrivate = 0 " +
                "AND noteType IN ('main', 'note') AND (audioPath IS NULL OR audioPath = '')", null)) {
            while (c.moveToNext()) out.add(new long[] {c.getLong(0), c.getLong(1), c.getLong(2)});
        }
        return out;
    }

    // ---- folders

    /** Live folders: id -> name. */
    List<Object[]> folders() {
        List<Object[]> out = new ArrayList<>();
        if (read() == null) return out;
        try (Cursor c = read.rawQuery("SELECT id, name FROM folders WHERE deletedAt IS NULL", null)) {
            while (c.moveToNext()) out.add(new Object[] {c.getLong(0), c.getString(1)});
        }
        return out;
    }

    String folderName(long id) {
        if (read() == null) return null;
        try (Cursor c = read.rawQuery("SELECT name FROM folders WHERE id = ? AND deletedAt IS NULL",
            new String[] {String.valueOf(id)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    long addFolder(String name, long createdAt, long updatedAt) throws Exception {
        // id 0 (Room assigns one), name, createdAt, updatedAt, legacyId, stickAt, sortOrder, deletedAt, deletionTag
        Object folder = folderConstructor.newInstance(0L, name, createdAt, updatedAt, null, 0L, 0, null, 0);
        return Dao.addFolder(db, folder);
    }
}
