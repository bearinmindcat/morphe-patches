package org.ungoogled.samsungmyfiles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samsung's "sectrash" provider, which only One UI has. My Files moves a trashed item to
 * {@code <volume>/Android/.Trash/<app>/<id>/<time deleted>/<original folder>/.!%#@$/<name>} itself and asks the
 * provider only to index it; off One UI every one of those calls fails ("Unknown URI"), which left the "Moving to the
 * Trash" dialog up forever and the Trash empty. This index is read from those folders on every query, so it can never
 * disagree with them; row deletes are no-ops because My Files moves and deletes the files itself. Like One UI it
 * empties an item 30 days after it was deleted.
 */
public final class TrashProvider extends ContentProvider {
    static final String AUTHORITY = "sectrash";
    private static final Uri FILES = Uri.parse("content://" + AUTHORITY + "/files");
    private static final String TRASH = "Android/.Trash";
    private static final String MARKER = ".!%#@$";
    private static final long KEEP = 30L * 24 * 60 * 60 * 1000;
    private static final Pattern MISSING_COLUMN = Pattern.compile("no such column: (?:\\w+\\.)?(\\w+)");
    private static final String TAG = "UA";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    private static long id(String path) {
        long id = UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits() >>> 12;
        return id == 0 ? 1 : id;
    }

    private static int mediaType(String mime) {
        if (mime == null) return 0;
        if (mime.startsWith("image/")) return 1;
        if (mime.startsWith("audio/")) return 2;
        if (mime.startsWith("video/")) return 3;
        return 0;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return type != null ? type : "application/octet-stream";
    }

    private static boolean deleteAll(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) deleteAll(child);
        }
        return file.delete();
    }

    private static final class Row {
        final ContentValues values = new ContentValues();
    }

    /** One indexed trash item, then its contents with it as their parent; answers its size (a folder's: its contents'). */
    private static long add(List<Row> rows, File file, String originalPath, long parent, long deleted, int user,
                            String volume) {
        String path = file.getAbsolutePath();
        long id = id(path);
        boolean dir = file.isDirectory();
        String name = file.getName();
        String mime = dir ? null : mime(name);
        Row row = new Row();
        ContentValues v = row.values;
        v.put("_id", id);
        v.put("_data", path);
        v.put("original_path", originalPath);
        v.put("_display_name", name);
        v.put("title", name);
        v.put("_size", dir ? 0 : file.length());
        v.put("mime_type", mime);
        v.put("media_type", mediaType(mime));
        v.put("date_deleted", deleted);
        v.put("date_expires", deleted + KEEP);
        v.put("date_modified", file.lastModified() / 1000);
        v.put("media_id", 0);
        v.put("parent", parent);
        v.put("is_cloud", 1);
        v.put("is_dir", dir ? 1 : 0);
        v.put("format", dir ? 0x3001 : 0);
        v.put("user_id", user);
        v.put("volume_name", volume);
        rows.add(row);
        if (!dir) return file.length();
        long size = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                size += add(rows, child, originalPath + "/" + child.getName(), id, deleted, user, volume);
            }
        }
        v.put("_size", size);
        return size;
    }

    /** The original folder is the path between the time folder and the marker. */
    private static void findMarkers(List<Row> rows, File dir, File timeDir, long deleted, String volume) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (!MARKER.equals(child.getName())) {
                findMarkers(rows, child, timeDir, deleted, volume);
                continue;
            }
            String parentPath = child.getParentFile().getAbsolutePath().substring(timeDir.getAbsolutePath().length());
            if (parentPath.startsWith("/mnt/media_rw")) parentPath = "/storage" + parentPath.substring("/mnt/media_rw".length());
            int user = Process.myUid() / 100000;
            Matcher m = Pattern.compile("^/storage/emulated/(\\d+)").matcher(parentPath);
            if (m.find()) user = Integer.parseInt(m.group(1));
            File[] items = child.listFiles();
            if (items == null) continue;
            for (File item : items) {
                add(rows, item, parentPath + "/" + item.getName(), 0, deleted, user, volume);
            }
        }
    }

    private List<File> volumes() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        Context context = getContext();
        StorageManager storage = context == null ? null : context.getSystemService(StorageManager.class);
        if (storage == null) return out;
        for (StorageVolume volume : storage.getStorageVolumes()) {
            File dir = volume.getDirectory();
            if (dir != null && !volume.isPrimary()) out.add(dir);
        }
        return out;
    }

    private void fill(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY, _data TEXT, original_path TEXT, _display_name TEXT,"
                + " title TEXT, _size INTEGER, mime_type TEXT, media_type INTEGER, date_deleted INTEGER,"
                + " date_expires INTEGER, date_modified INTEGER, media_id INTEGER, parent INTEGER, is_cloud INTEGER,"
                + " is_dir INTEGER, format INTEGER, user_id INTEGER, volume_name TEXT)");
        List<Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        File primary = Environment.getExternalStorageDirectory();
        for (File root : volumes()) {
            String volumeName = root.equals(primary) ? "external_primary" : root.getName().toLowerCase();
            File[] apps = new File(root, TRASH).listFiles();
            if (apps == null) continue;
            for (File app : apps) {
                File[] ids = app.listFiles();
                if (ids == null) continue;
                for (File idDir : ids) {
                    File[] times = idDir.listFiles();
                    if (times == null) continue;
                    for (File time : times) {
                        long deleted;
                        try {
                            deleted = Long.parseLong(time.getName());
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        if (now > deleted + KEEP) {
                            // Expired, as One UI's own trash empties it.
                            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "trash expired: " + time);
                            deleteAll(time);
                            continue;
                        }
                        findMarkers(rows, time, time, deleted, volumeName);
                    }
                }
            }
        }
        db.beginTransaction();
        try {
            for (Row row : rows) db.insertWithOnConflict("files", null, row.values, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static MatrixCursor copy(Cursor c) {
        MatrixCursor out = new MatrixCursor(c.getColumnNames(), c.getCount());
        int n = c.getColumnCount();
        while (c.moveToNext()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER: row[i] = c.getLong(i); break;
                    case Cursor.FIELD_TYPE_FLOAT: row[i] = c.getDouble(i); break;
                    case Cursor.FIELD_TYPE_STRING: row[i] = c.getString(i); break;
                    case Cursor.FIELD_TYPE_BLOB: row[i] = c.getBlob(i); break;
                    default: row[i] = null;
                }
            }
            out.addRow(row);
        }
        return out;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                                     String sortOrder) {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            fill(db);
            String sql = SQLiteQueryBuilder.buildQueryString(false, "files", projection, selection, null, null,
                    sortOrder, uri.getQueryParameter("limit"));
            for (int attempt = 0; ; attempt++) {
                try (Cursor c = db.rawQuery(sql, selectionArgs)) {
                    MatrixCursor out = copy(c);
                    if (getContext() != null) out.setNotificationUri(getContext().getContentResolver(), FILES);
                    return out;
                } catch (SQLiteException e) {
                    Matcher m = MISSING_COLUMN.matcher(String.valueOf(e.getMessage()));
                    if (attempt > 8 || !m.find()) throw e;
                    // A column One UI's index has and this one does not: there, but empty.
                    db.execSQL("ALTER TABLE files ADD COLUMN " + m.group(1));
                }
            }
        } finally {
            db.close();
        }
    }

    private void changed() {
        if (getContext() != null) getContext().getContentResolver().notifyChange(FILES, null);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // "scan" after a move to the Trash: the index is read from the folders, so only tell the observers.
        changed();
        return new Bundle();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        changed();
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        changed();
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        changed();
        return 0;
    }
}
