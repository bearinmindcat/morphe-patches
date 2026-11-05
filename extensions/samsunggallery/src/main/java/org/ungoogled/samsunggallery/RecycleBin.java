package org.ungoogled.samsunggallery;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gallery's Recycle bin, backed by Android's own trash (MediaStore items with IS_TRASHED, which Android keeps 30 days
 * and then deletes) instead of Samsung's: One UI's Gallery moves deleted files into its own hidden folder, which an
 * app that is not the system's may not do with other apps' files. Gallery's trash screens read their items through
 * its ITrashProvider; this answers them in that provider's own columns, from a table rebuilt whenever MediaStore
 * changes.
 *
 * Android does not let a gallery read the file of another app's trashed photo by its path (only by its content URI),
 * and Gallery decodes trash items by path. Each item is therefore shown from a preview Gallery can read, made when the
 * item is trashed (or the first time the bin shows it), kept in the app's own files; a trashed video plays from its
 * content URI.
 */
public final class RecycleBin implements InvocationHandler {
    private static final String TAG = MediaRequests.TAG;
    private static final String ITRASH_PROVIDER = "com.samsung.android.gallery.module.trash.abstraction.ITrashProvider";
    /** Android's trashed files are named ".trashed-<expiry in seconds>-<original name>". */
    private static final Pattern TRASHED_NAME = Pattern.compile("^\\.trashed-\\d+-");
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** How long Android keeps a trashed item (MediaProvider's default). */
    private static final int KEEP_DAYS = 30;
    private static final int PREVIEW_SIZE = 2048;
    private static final String[] TRASH_COLUMNS = {
            "__absID", "__absPath", "__Title", "__mediaType", "__width", "__height", "__orientation", "__orientationTag",
            "__originPath", "__originTitle", "__deleteTime", "__storageType", "__burstGroupID", "__bestImage",
            "__cloudServerId", "__cloudTP", "__restoreExtra", "__volumeName", "__expiredPeriod", "__database_version",
            "__mimeType", "__size", "__dateTaken", "__fileDuration", "__expiredTime"};
    private static final String TRASH_PROJECTION = "__absID, __absPath, __Title, __mediaType, __width, __height, "
            + "__orientation, __orientationTag, __originPath, __originTitle, __deleteTime, __storageType, "
            + "__burstGroupID AS __GroupMediaID, __bestImage AS __GroupMediaBest, __cloudServerId, __cloudTP, "
            + "__restoreExtra, __volumeName, __expiredPeriod, __database_version, 0 AS __count, __mimeType, __size, "
            + "__dateTaken, __fileDuration";

    private static volatile Object provider;
    private static SQLiteDatabase table;
    private static long syncedGeneration = -1;
    private static String syncedVersion;

    private final Context context;

    private RecycleBin(Context context) {
        this.context = context.getApplicationContext();
    }

    /** TrashProviderFactory.getInstance: Gallery's trash provider, as an ITrashProvider. */
    public static Object provider(Context context) {
        Object p = provider;
        if (p != null) return p;
        synchronized (RecycleBin.class) {
            if (provider == null) {
                try {
                    Class<?> type = Class.forName(ITRASH_PROVIDER, false, RecycleBin.class.getClassLoader());
                    provider = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new RecycleBin(context));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Gallery has no " + ITRASH_PROVIDER, e);
                }
            }
            return provider;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        int n = args == null ? 0 : args.length;
        try {
            switch (name) {
                case "getTrashCursor":
                    if (n == 1 && args[0] instanceof Boolean) return query("SELECT " + TRASH_PROJECTION + " FROM trash ORDER BY __deleteTime DESC", null);
                    if (n == 1 && args[0] instanceof Long) return query("SELECT " + TRASH_PROJECTION + " FROM trash WHERE __absID = " + args[0], null);
                    if (n == 1) return query("SELECT " + TRASH_PROJECTION + " FROM trash WHERE __absID IN (" + args[0] + ") ORDER BY __deleteTime DESC", null);
                    return query("SELECT __originPath FROM trash" + where((String) args[0]), (String[]) args[1]);
                case "getTrashCount": {
                    int[] counts = {0, 0};
                    try (Cursor c = query("SELECT __mediaType, count(*) FROM trash GROUP BY __mediaType", null)) {
                        while (c.moveToNext()) {
                            if (c.getInt(0) == 1) counts[0] = c.getInt(1);
                            else if (c.getInt(0) == 3) counts[1] = c.getInt(1);
                        }
                    }
                    return counts;
                }
                case "getTrashTotalCount":
                    try (Cursor c = query("SELECT count(*) FROM trash", null)) {
                        return c.moveToFirst() ? c.getInt(0) : 0;
                    }
                case "loadTrashIdsCursor":
                    return query("SELECT group_concat(__absID) AS __idList, count(*) AS __count FROM "
                            + "(SELECT __absID FROM trash ORDER BY __deleteTime DESC)", null);
                case "loadTrashRealRatioCursor":
                    return query("SELECT group_concat(CASE WHEN height IS NULL OR height = 0 OR width IS NULL OR width = 0 THEN 1 "
                            + "ELSE substr(CASE WHEN orientation = 90 OR orientation = 270 THEN 1.0 * height / width "
                            + "ELSE 1.0 * width / height END, 1, 4) END) AS __widthList, count(*) AS __count FROM "
                            + "(SELECT __width AS width, __height AS height, __orientation AS orientation FROM trash "
                            + "ORDER BY __deleteTime DESC)", null);
                case "getExpiredTrashCursor":
                    // Android deletes expired items itself.
                    return new MatrixCursor(new String[]{"__storageType", "__absPath", "__originTitle", "__mediaType",
                            "__cloudServerId", "__cloudTP", "__restoreExtra", "__volumeName"});
                case "getTrashUri":
                    return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
                case "getTrashInfo":
                    return new Object[0];
                case "isSameOriginFileHashExists":
                    return false;
                case "isSameRecordExist":
                    return -1;
                case "insertTrash":
                    return null;
                case "deleteAllTrash":
                    // Turning the Trash off in Gallery's settings deletes what is in it (Gallery says so).
                    return deleteAll();
                case "deleteTrash":
                case "updateTrash":
                    // The bin is Android's; Gallery's own bookkeeping of it has nothing to change.
                    return 0;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "RecycleBin(MediaStore trash)";
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "recycle bin: " + name + ": " + e);
            return fallback(method.getReturnType());
        }
        Log.w(TAG, "recycle bin: unknown call " + name);
        return fallback(method.getReturnType());
    }

    private static Object fallback(Class<?> type) {
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == int[].class) return new int[]{0, 0};
        if (type == Object[].class) return new Object[0];
        return null;
    }

    private static String where(String selection) {
        return TextUtils.isEmpty(selection) ? "" : " WHERE " + selection;
    }

    /** Deletes every trashed photo and video for good (one request); answers how many are gone. */
    private int deleteAll() {
        long[] ids;
        boolean[] videos;
        synchronized (RecycleBin.class) {
            sync();
            try (Cursor c = table.rawQuery("SELECT __absID, __mediaType FROM trash", null)) {
                ids = new long[c.getCount()];
                videos = new boolean[ids.length];
                for (int i = 0; c.moveToNext(); i++) {
                    ids[i] = c.getLong(0);
                    videos[i] = c.getInt(1) == 3;
                }
            }
        }
        if (ids.length == 0) return 0;
        MediaRequests.apply(context, MediaRequests.DELETE, ids, videos);
        int gone = ids.length - MediaRequests.states(context, ids).size();
        Log.i(TAG, "recycle bin: emptied " + gone + "/" + ids.length);
        return gone;
    }

    /** The answer as a copy: the table is rebuilt under any cursor Gallery keeps. */
    private Cursor query(String sql, String[] args) {
        synchronized (RecycleBin.class) {
            sync();
            try (Cursor c = table.rawQuery(sql, args)) {
                MatrixCursor copy = new MatrixCursor(c.getColumnNames(), c.getCount());
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
                    copy.addRow(row);
                }
                return copy;
            }
        }
    }

    /** Rebuilds the table from MediaStore's trashed photos and videos when MediaStore changed since last time. */
    private void sync() {
        String version = MediaStore.getVersion(context);
        long generation = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        if (table != null && generation == syncedGeneration && version.equals(syncedVersion)) return;
        if (table == null) {
            table = SQLiteDatabase.create(null);
            StringBuilder create = new StringBuilder("CREATE TABLE trash (__absID INTEGER PRIMARY KEY");
            for (int i = 1; i < TRASH_COLUMNS.length; i++) create.append(", ").append(TRASH_COLUMNS[i]);
            table.execSQL(create.append(")").toString());
        }
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1, 3)");
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        String[] projection = {"_id", "_data", "_display_name", "media_type", "width", "height", "orientation",
                "date_expires", "mime_type", "_size", "datetaken", "duration", "volume_name"};
        Set<Long> present = new HashSet<>();
        // A mirrored photo (EXIF orientation 2, 4, 5, 7) shows mirrored in the bin too.
        Map<Long, Integer> mirrored = MediaMirror.mirroredPhotos();
        table.beginTransaction();
        try (Cursor c = context.getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)) {
            table.execSQL("DELETE FROM trash");
            ContentValues v = new ContentValues();
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                String data = c.getString(1);
                boolean video = c.getInt(3) == 3;
                File parent = data == null ? null : new File(data).getParentFile();
                String name = original(data == null ? c.getString(2) : new File(data).getName());
                long expires = c.getLong(7) * 1000;
                if (expires <= 0) expires = System.currentTimeMillis() + KEEP_DAYS * DAY;
                String shown = previewPath(context, id, video);
                present.add(id);
                v.clear();
                v.put("__absID", id);
                v.put("__absPath", shown);
                v.put("__Title", name);
                v.put("__mediaType", video ? 3 : 1);
                v.put("__width", c.getInt(4));
                v.put("__height", c.getInt(5));
                // A video's preview frame comes out of MediaMetadataRetriever already turned upright.
                v.put("__orientation", video ? 0 : c.getInt(6));
                Integer tag = video ? null : mirrored.get(id);
                v.put("__orientationTag", tag == null ? 0 : tag);
                v.put("__originPath", parent == null ? name : new File(parent, name).getPath());
                v.put("__originTitle", name);
                v.put("__deleteTime", expires - KEEP_DAYS * DAY);
                v.put("__expiredTime", expires);
                v.put("__storageType", 1);
                v.put("__burstGroupID", 0);
                v.put("__bestImage", 0);
                v.put("__volumeName", Environment.getExternalStorageDirectory().getAbsolutePath());
                v.put("__expiredPeriod", KEEP_DAYS);
                v.put("__database_version", 0);
                v.put("__mimeType", c.getString(8));
                v.put("__size", c.getLong(9));
                v.put("__dateTaken", c.getLong(10));
                v.put("__fileDuration", c.getLong(11));
                table.insert("trash", null, v);
            }
            table.setTransactionSuccessful();
        } finally {
            table.endTransaction();
        }
        syncedGeneration = generation;
        syncedVersion = version;
        prunePreviews(context, present);
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "recycle bin: " + present.size() + " items");
    }

    /** "IMG_1.jpg" from Android's trashed name ".trashed-1767225600-IMG_1.jpg". */
    static String original(String name) {
        if (name == null) return null;
        Matcher m = TRASHED_NAME.matcher(name);
        return m.find() ? name.substring(m.end()) : name;
    }

    // ---- previews ----

    private static File previewDir(Context context) {
        File dir = new File(context.getFilesDir(), "recycle_bin");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "recycle bin: no preview folder");
        return dir;
    }

    static File previewFile(Context context, long id) {
        return new File(previewDir(context), id + ".jpg");
    }

    /** The preview to show for an item, made now from the item itself when there is none yet. */
    private static String previewPath(Context context, long id, boolean video) {
        File file = previewFile(context, id);
        if (!file.isFile()) keepPreview(context, id, video);
        return file.getPath();
    }

    /** Writes a preview of a photo or video Gallery can read later by path (read now through its content URI). */
    static void keepPreview(Context context, long id, boolean video) {
        File file = previewFile(context, id);
        if (file.isFile()) return;
        Uri uri = MediaRequests.uri(id, video);
        Bitmap bitmap = null;
        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return;
            if (video) {
                // MediaProvider's own thumbnail (usually made already for the timeline), else a frame of our own.
                try {
                    bitmap = context.getContentResolver().loadThumbnail(uri, new Size(PREVIEW_SIZE / 2, PREVIEW_SIZE / 2), null);
                } catch (IOException | RuntimeException e) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(fd.getFileDescriptor());
                        bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } finally {
                        retriever.release();
                    }
                }
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, bounds);
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIZE) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                try (ParcelFileDescriptor again = context.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (again != null) bitmap = BitmapFactory.decodeFileDescriptor(again.getFileDescriptor(), null, options);
                }
            }
            if (bitmap == null) {
                Log.w(TAG, "recycle bin: no preview for " + uri);
                return;
            }
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > PREVIEW_SIZE) {
                float scale = (float) PREVIEW_SIZE / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "recycle bin: preview not saved");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "recycle bin: preview for " + uri + " failed: " + e);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /**
     * The MediaStore id of a bin item from the path Gallery shows it by (Gallery's restore and delete steps keep only
     * that): its preview, named by the id.
     */
    static long idOf(Context context, String path) {
        if (!isPreview(path)) return -1;
        String name = new File(path).getName();
        try {
            return Long.parseLong(name.substring(0, name.length() - ".jpg".length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }

    /**
     * GedMediaPlayerCompat.setDataSource(path): a trashed video shown from its preview plays from its content URI,
     * which Android lets Gallery read (its path it does not). Null for any other path.
     */
    public static Uri videoUri(String path) {
        if (!isPreview(path)) return null;
        Context context = MediaMirror.context();
        if (context == null) return null;
        long id = idOf(context, path);
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        Uri uri = MediaRequests.uri(id, true);
        try (Cursor c = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns._ID}, args, null)) {
            return c != null && c.moveToFirst() ? uri : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether Gallery is showing one of these previews (a still image, also for a video). */
    public static boolean isPreview(String path) {
        return path != null && path.contains("/files/recycle_bin/");
    }

    static void dropPreview(Context context, long id) {
        File file = previewFile(context, id);
        if (file.exists() && !file.delete()) Log.w(TAG, "recycle bin: preview not deleted");
    }

    /**
     * Previews of items no longer in the bin (restored, deleted, or expired and deleted by Android). Recent ones stay:
     * a preview is made just before its item is moved to the bin.
     */
    private static void prunePreviews(Context context, Set<Long> present) {
        File[] files = previewDir(context).listFiles();
        if (files == null) return;
        long recent = System.currentTimeMillis() - 15 * 60 * 1000;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".jpg") || f.lastModified() > recent) continue;
            try {
                if (!present.contains(Long.parseLong(n.substring(0, n.length() - 4)))) f.delete();
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
