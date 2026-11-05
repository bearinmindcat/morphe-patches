package org.ungoogled.samsunggallery;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.media.ExifInterface;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One UI's media provider keeps each photo's EXIF orientation value (orientation_tag) next to its rotation, and Gallery
 * mirrors a photo whose value says so (2, 4, 5, 7: a selfie saved as previewed). MediaStore keeps only the rotation, so
 * mirrored photos showed unmirrored. The copy reads the value from the photos themselves, once per version of a photo
 * (kept in the saved database): new photos at once, a whole library the first time in the background.
 */
final class ExifOrientation {
    private static final String TAG = "UA";
    /** More unread photos than this are read in the background, so that Gallery's first query does not wait. */
    private static final int AT_ONCE = 64;
    private static final int CHUNK = 200;
    private static final String TYPES = "('image/jpeg','image/heic','image/heif','image/webp','image/x-adobe-dng')";
    private static final String MATCH = "e._id = files._id AND e.date_modified IS files.date_modified AND e._size IS files._size";
    private static final AtomicBoolean reading = new AtomicBoolean();

    private ExifOrientation() {
    }

    private static final class Photo {
        final long id;
        final String path;
        final Long modified;
        final Long size;
        int tag;

        Photo(Cursor c) {
            id = c.getLong(0);
            path = c.getString(1);
            modified = c.isNull(2) ? null : c.getLong(2);
            size = c.isNull(3) ? null : c.getLong(3);
        }
    }

    static void createTable(SQLiteDatabase d, String schema) {
        d.execSQL("CREATE TABLE IF NOT EXISTS " + schema + ".exif_orientation (_id INTEGER PRIMARY KEY, "
                + "date_modified INTEGER, _size INTEGER, tag INTEGER)");
    }

    /**
     * Fills files.orientation_tag: the value that goes with the photo's rotation, then every value read from a photo.
     * Runs inside the copy's sync.
     */
    static void fill(MediaMirror mirror, SQLiteDatabase d, String schema) {
        String table = schema + ".exif_orientation";
        d.execSQL("UPDATE files SET orientation_tag = CASE WHEN media_type = 1 THEN CASE orientation WHEN 90 THEN 6 "
                + "WHEN 180 THEN 3 WHEN 270 THEN 8 ELSE 1 END ELSE 0 END");
        d.execSQL("UPDATE files SET orientation_tag = (SELECT e.tag FROM " + table + " e WHERE " + MATCH + ") "
                + "WHERE EXISTS (SELECT 1 FROM " + table + " e WHERE " + MATCH + " AND e.tag > 0)");
        if (reading.get()) return;
        List<Photo> unread = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT _id, _data, date_modified, _size FROM files WHERE media_type = 1 AND mime_type IN "
                + TYPES + " AND NOT EXISTS (SELECT 1 FROM " + table + " e WHERE " + MATCH + ")", null)) {
            while (c.moveToNext()) unread.add(new Photo(c));
        }
        if (unread.isEmpty()) return;
        if (unread.size() <= AT_ONCE) {
            for (Photo p : unread) read(p);
            store(d, table, unread);
            return;
        }
        if (!reading.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            long started = System.currentTimeMillis();
            int mirrored = 0;
            try {
                for (int i = 0; i < unread.size(); i += CHUNK) {
                    List<Photo> chunk = unread.subList(i, Math.min(unread.size(), i + CHUNK));
                    for (Photo p : chunk) {
                        read(p);
                        if (isMirror(p.tag)) mirrored++;
                    }
                    synchronized (mirror) {
                        store(mirror.database(), table, chunk);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "mirror: orientation of photos not read: " + e);
            } finally {
                reading.set(false);
            }
            Log.i(TAG, "mirror: orientation of " + unread.size() + " photos read in "
                    + (System.currentTimeMillis() - started) + " ms, " + mirrored + " mirrored");
        }, "UA-exif");
        t.start();
    }

    static boolean isMirror(int tag) {
        return tag == 2 || tag == 4 || tag == 5 || tag == 7;
    }

    private static void read(Photo p) {
        try {
            p.tag = p.path == null ? 0 : new ExifInterface(p.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);
        } catch (Exception | OutOfMemoryError e) {
            p.tag = 0;
        }
    }

    /** Keeps the values read and puts them in the copy (for the photo's current version only). */
    private static void store(SQLiteDatabase d, String table, List<Photo> photos) {
        boolean outer = d.inTransaction();
        if (!outer) d.beginTransaction();
        try (SQLiteStatement keep = d.compileStatement("INSERT OR REPLACE INTO " + table
                + " (_id, date_modified, _size, tag) VALUES (?, ?, ?, ?)");
             SQLiteStatement use = d.compileStatement("UPDATE files SET orientation_tag = ? WHERE _id = ? "
                     + "AND date_modified IS ? AND _size IS ?")) {
            for (Photo p : photos) {
                keep.bindLong(1, p.id);
                bindOrNull(keep, 2, p.modified);
                bindOrNull(keep, 3, p.size);
                keep.bindLong(4, p.tag);
                keep.executeInsert();
                // 0 = no value in the file: the rotation's own value stays.
                if (p.tag > 0) {
                    use.bindLong(1, p.tag);
                    use.bindLong(2, p.id);
                    bindOrNull(use, 3, p.modified);
                    bindOrNull(use, 4, p.size);
                    use.executeUpdateDelete();
                }
            }
            if (!outer) d.setTransactionSuccessful();
        } finally {
            if (!outer) d.endTransaction();
        }
    }

    private static void bindOrNull(SQLiteStatement s, int index, Long value) {
        if (value == null) s.bindNull(index);
        else s.bindLong(index, value);
    }
}
