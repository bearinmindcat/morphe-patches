package org.ungoogled.samsunggallery;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.media.ExifInterface;
import android.os.Process;
import android.util.Log;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * One UI's media provider keeps each photo's EXIF orientation value (orientation_tag) next to its rotation, and Gallery
 * mirrors a photo whose value says so (2, 4, 5, 7: a selfie saved as previewed). MediaStore keeps only the rotation, so
 * mirrored photos showed unmirrored. The copy reads the value from the photos themselves, once per version of a photo
 * (kept in the saved database): new photos at once, a whole library the first time in the background.
 *
 * The same read gives the date a photo was taken where MediaStore has none. Android takes the EXIF date only with its time
 * zone (an offset or GPS time in the file) or within a day of the file's date, so a photo copied or restored later (a new
 * file date) got no date taken, and Gallery's "date created" (and its timeline) fell back to the file's date: old photos
 * came first. One UI's provider takes the EXIF date as the phone's local time, and so does the copy now.
 */
final class ExifOrientation {
    private static final String TAG = "UA";
    /** Unread photos read during the sync (the newest); the rest are read in the background, so Gallery's query does not wait. */
    private static final int AT_ONCE = 64;
    private static final int CHUNK = 200;
    private static final String TYPES = "('image/jpeg','image/heic','image/heif','image/webp','image/x-adobe-dng')";
    private static final String MATCH = "e._id = files._id AND e.date_modified IS files.date_modified AND e._size IS files._size";
    /** Read: the orientation, and the date taken unless MediaStore has it (taken is NULL in values read before dates were). */
    private static final String READ = MATCH + " AND (e.taken IS NOT NULL OR files.datetaken IS NOT NULL)";
    private static final Pattern EXIF_DATE = Pattern.compile("\\d{4}[:-]\\d{2}[:-]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*");
    private static final Pattern EXIF_OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}");
    private static final AtomicBoolean reading = new AtomicBoolean();
    /** Photos the background read has taken. */
    private static final Set<Long> IN_FLIGHT = new HashSet<>();

    private ExifOrientation() {
    }

    private static final class Photo {
        final long id;
        final String path;
        final Long modified;
        final Long size;
        int tag;
        /** EXIF date taken in ms, 0 = none in the file. */
        long taken;

        Photo(Cursor c) {
            id = c.getLong(0);
            path = c.getString(1);
            modified = c.isNull(2) ? null : c.getLong(2);
            size = c.isNull(3) ? null : c.getLong(3);
        }
    }

    static void createTable(SQLiteDatabase d, String schema) {
        d.execSQL("CREATE TABLE IF NOT EXISTS " + schema + ".exif_orientation (_id INTEGER PRIMARY KEY, "
                + "date_modified INTEGER, _size INTEGER, tag INTEGER, taken INTEGER)");
        boolean taken = false;
        try (Cursor c = d.rawQuery("PRAGMA " + schema + ".table_info(exif_orientation)", null)) {
            while (c.moveToNext()) taken |= "taken".equals(c.getString(1));
        }
        // Kept by an earlier version: photos without a MediaStore date are read again for their EXIF date.
        if (!taken) d.execSQL("ALTER TABLE " + schema + ".exif_orientation ADD COLUMN taken INTEGER");
    }

    /**
     * Fills files.orientation_tag: the value that goes with the photo's rotation, then every value read from a photo.
     * The newest unread photos (a new selfie) are read at once; the rest of a library read the first time is read in the
     * background, and Gallery's lists reload when that read is done (MediaMirror.orientationsRead) -- a list loaded
     * before showed those photos unmirrored while screens loaded later showed them mirrored. Runs inside the copy's sync.
     */
    static void fill(MediaMirror mirror, SQLiteDatabase d, String schema) {
        String table = schema + ".exif_orientation";
        d.execSQL("UPDATE files SET orientation_tag = CASE WHEN media_type = 1 THEN CASE orientation WHEN 90 THEN 6 "
                + "WHEN 180 THEN 3 WHEN 270 THEN 8 ELSE 1 END ELSE 0 END");
        d.execSQL("UPDATE files SET orientation_tag = (SELECT e.tag FROM " + table + " e WHERE " + MATCH + ") "
                + "WHERE EXISTS (SELECT 1 FROM " + table + " e WHERE " + MATCH + " AND e.tag > 0)");
        // Gallery's date taken (datetime, the copy's COALESCE of MediaStore's dates) from the EXIF date where MediaStore
        // has none.
        d.execSQL("UPDATE files SET datetaken = (SELECT e.taken FROM " + table + " e WHERE " + MATCH + "), "
                + "datetime = (SELECT e.taken FROM " + table + " e WHERE " + MATCH + ") "
                + "WHERE datetaken IS NULL AND EXISTS (SELECT 1 FROM " + table + " e WHERE " + MATCH + " AND e.taken > 0)");
        List<Photo> unread = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT _id, _data, date_modified, _size FROM files WHERE media_type = 1 AND mime_type IN "
                + TYPES + " AND NOT EXISTS (SELECT 1 FROM " + table + " e WHERE " + READ + ") "
                + "ORDER BY datetime DESC, _id DESC", null)) {
            synchronized (IN_FLIGHT) {
                while (c.moveToNext()) {
                    if (!IN_FLIGHT.contains(c.getLong(0))) unread.add(new Photo(c));
                }
            }
        }
        List<Photo> now = unread.subList(0, Math.min(AT_ONCE, unread.size()));
        for (Photo p : now) read(p);
        if (!now.isEmpty()) store(d, table, now);
        publishMirrored(d);
        if (unread.isEmpty()) return;
        List<Photo> later = new ArrayList<>(unread.subList(now.size(), unread.size()));
        // A read already running takes the rest when it ends.
        if (later.isEmpty() || !reading.compareAndSet(false, true)) return;
        synchronized (IN_FLIGHT) {
            for (Photo p : later) IN_FLIGHT.add(p.id);
        }
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            long started = System.currentTimeMillis();
            int mirrored = 0;
            try {
                for (int i = 0; i < later.size(); i += CHUNK) {
                    List<Photo> chunk = later.subList(i, Math.min(later.size(), i + CHUNK));
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
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.clear();
                }
                reading.set(false);
            }
            Log.i(TAG, "mirror: orientation of " + later.size() + " photos read in "
                    + (System.currentTimeMillis() - started) + " ms, " + mirrored + " mirrored");
            mirror.orientationsRead();
        }, "UA-exif");
        t.start();
    }

    /** Tells MirroredPhotos which photos are mirrored (their cached thumbnails get keys of their own). */
    private static void publishMirrored(SQLiteDatabase d) {
        Set<String> paths = new HashSet<>();
        try (Cursor c = d.rawQuery("SELECT _data FROM files WHERE media_type = 1 AND orientation_tag IN (2, 4, 5, 7) "
                + "AND _data IS NOT NULL", null)) {
            while (c.moveToNext()) paths.add(c.getString(0));
        }
        MirroredPhotos.setPaths(paths);
    }

    static boolean isMirror(int tag) {
        return tag == 2 || tag == 4 || tag == 5 || tag == 7;
    }

    private static void read(Photo p) {
        p.tag = 0;
        p.taken = 0;
        if (p.path == null) return;
        try {
            ExifInterface exif = new ExifInterface(p.path);
            p.tag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);
            p.taken = taken(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
            if (p.taken == 0) {
                p.taken = taken(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED));
            }
        } catch (Exception | OutOfMemoryError e) {
            // Unreadable: no values.
        }
    }

    /**
     * An EXIF date ("2015:07:04 12:00:00") in ms: at its offset when the file has one, else in the phone's time zone; 0 when
     * missing or not a real date (zeros, before 1971, more than a day ahead).
     */
    static long taken(String value, String offset) {
        if (value == null) return 0;
        value = value.trim();
        if (!EXIF_DATE.matcher(value).matches()) return 0;
        String normal = value.substring(0, 4) + ":" + value.substring(5, 7) + ":" + value.substring(8, 10) + " " + value.substring(11, 19);
        SimpleDateFormat format = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        format.setLenient(false);
        format.setTimeZone(offset != null && EXIF_OFFSET.matcher(offset.trim()).matches()
                ? TimeZone.getTimeZone("GMT" + offset.trim()) : TimeZone.getDefault());
        Date date = format.parse(normal, new ParsePosition(0));
        if (date == null) return 0;
        long ms = date.getTime();
        if (ms < 31536000000L || ms > System.currentTimeMillis() + 86400000L) return 0;
        return ms;
    }

    /** Keeps the values read and puts them in the copy (for the photo's current version only). */
    private static void store(SQLiteDatabase d, String table, List<Photo> photos) {
        boolean outer = d.inTransaction();
        if (!outer) d.beginTransaction();
        try (SQLiteStatement keep = d.compileStatement("INSERT OR REPLACE INTO " + table
                + " (_id, date_modified, _size, tag, taken) VALUES (?, ?, ?, ?, ?)");
             SQLiteStatement use = d.compileStatement("UPDATE files SET orientation_tag = ? WHERE _id = ? "
                     + "AND date_modified IS ? AND _size IS ?");
             SQLiteStatement date = d.compileStatement("UPDATE files SET datetaken = ?, datetime = ? WHERE _id = ? "
                     + "AND datetaken IS NULL AND date_modified IS ? AND _size IS ?")) {
            for (Photo p : photos) {
                keep.bindLong(1, p.id);
                bindOrNull(keep, 2, p.modified);
                bindOrNull(keep, 3, p.size);
                keep.bindLong(4, p.tag);
                keep.bindLong(5, p.taken);
                keep.executeInsert();
                if (p.taken > 0) {
                    date.bindLong(1, p.taken);
                    date.bindLong(2, p.taken);
                    date.bindLong(3, p.id);
                    bindOrNull(date, 4, p.modified);
                    bindOrNull(date, 5, p.size);
                    date.executeUpdateDelete();
                }
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
