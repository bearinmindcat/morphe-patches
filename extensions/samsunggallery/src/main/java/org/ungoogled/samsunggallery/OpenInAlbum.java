package org.ungoogled.samsunggallery;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A photo opened from another app showed alone: Gallery opens a photo within its album (swipe to the ones around it)
 * only when the intent names the album -- Samsung's camera passes its own extras, Samsung's My Files the photo's path --
 * and it does not handle the camera's REVIEW action at all. Cameras (GCam, and GCam through a "Photos" stub that turns
 * REVIEW into VIEW) send only the photo's MediaStore URI; file managers send their own URI for the file (Android's
 * Files app a document URI, others a file:// URI or one of their own). Each is traced back to the photo's MediaStore
 * item, which the intent then names together with its album (bucketId, the extra My Files uses), so Gallery's own
 * album viewer opens.
 */
public final class OpenInAlbum {
    private static final String TAG = "UA";
    private static final String CAMERA_REVIEW = "com.android.camera.action.REVIEW";
    private static final String PROVIDER_REVIEW = "android.provider.action.REVIEW";
    /**
     * Gallery's extras naming albums, a list or a camera hand-off, and those of Gallery's own viewer launches ("open in
     * other window", its file browser): Gallery already knows what to show.
     */
    private static final String[] LIST_EXTRAS = {"bucketIds", "from-Camera", "viewbuckets", "uriListData", "useUriList",
            "QueryParams", "open_in_other_window", "quick-view-internal"};
    /** Gallery's extras naming the photo's album (My Files'): enough with a MediaStore URI, which Gallery needs too. */
    private static final String[] ALBUM_EXTRAS = {"bucketId", "AbsolutePath"};
    private static final String EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents";
    private static final String MEDIA_DOCUMENTS = "com.android.providers.media.documents";
    private static final String DOWNLOAD_DOCUMENTS = "com.android.providers.downloads.documents";
    /** Longest wait for the photo's row, under Android's 5 s limit for a screen that does not answer a touch. */
    private static final long ALBUM_ITEM_WAIT_MS = 4500;

    private OpenInAlbum() {
    }

    /**
     * Gallery's wait (2 s, on the main thread) for the photo's row before it opens the album; when it runs out Gallery
     * opens nothing (a blank screen). On a cold start the copy of MediaStore is made before the first answer, which can
     * take longer with a large library: waits up to 4.5 s.
     */
    public static boolean awaitAlbumItem(CountDownLatch latch, long time, TimeUnit unit) throws InterruptedException {
        if (latch.await(time, unit)) return true;
        long more = ALBUM_ITEM_WAIT_MS - unit.toMillis(time);
        if (more <= 0) return false;
        Log.i(TAG, "open in album: still copying the media list, waiting " + more + " ms more");
        return latch.await(more, TimeUnit.MILLISECONDS);
    }

    /** Called with the intent Gallery was started with, before Gallery reads it. */
    public static void prepare(Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            boolean review = CAMERA_REVIEW.equals(action) || PROVIDER_REVIEW.equals(action);
            if (!review && !Intent.ACTION_VIEW.equals(action)) return;
            // Gallery handles VIEW (shows the photo) but not REVIEW, which a camera sends for the photo just taken.
            if (review) intent.setAction(Intent.ACTION_VIEW);
            Uri uri = intent.getData();
            if (uri == null) return;
            String type = intent.getType();
            if (type != null && (type.startsWith("vnd.android.cursor.dir") || type.startsWith("vnd.android.document/"))) return;
            for (String extra : LIST_EXTRAS) {
                if (intent.hasExtra(extra)) return;
            }
            boolean album = false;
            for (String extra : ALBUM_EXTRAS) album |= intent.hasExtra(extra);
            if (album && isMediaStore(uri)) return;
            Context context = MediaMirror.context();
            if (context == null) return;
            Uri item = mediaItem(context, uri);
            if (item == null) {
                Log.i(TAG, "open in album: " + source(uri) + " item not in the media list, shown alone");
                return;
            }
            try (Cursor c = context.getContentResolver().query(item, new String[]{
                    MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                if (c == null || !c.moveToFirst() || c.isNull(0)) return;
                int bucket = (int) c.getLong(0);
                if (bucket == 0) return;
                // The album viewer finds the photo in its album by its MediaStore item; Gallery reads it with its own
                // photo and video access, which works after the opening app's grant is gone.
                if (!item.equals(uri)) intent.setDataAndType(item, type);
                if (!album) intent.putExtra("bucketId", bucket);
                // Gallery waits only 2 s for the photo's row in the album; on a cold start the copy is made first.
                MediaMirror.warmUp();
                Log.i(TAG, "open in album: " + source(uri) + " -> " + item + " in " + c.getString(1)
                        + (review ? " (camera review)" : ""));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "open in album: " + e);
        }
    }

    /** The MediaStore item (images/media/id or video/media/id) behind a URI another app sent, or null. */
    static Uri mediaItem(Context context, Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) return byPath(context, uri.getPath());
        if (!"content".equals(scheme)) return null;
        String authority = uri.getAuthority();
        if (authority == null) return null;
        if (isMediaStore(uri)) {
            long id;
            try {
                id = ContentUris.parseId(uri);
            } catch (RuntimeException notAnItem) {
                return null;
            }
            return id < 0 ? null : byId(context, id);
        }
        if (isDocument(context, uri)) {
            Uri item = byDocument(context, uri);
            if (item != null) return item;
            // Android's own answer for document URIs (it asks the documents provider for the file).
            try {
                Uri media = MediaStore.getMediaUri(context, uri);
                if (media != null) {
                    long id = ContentUris.parseId(media);
                    if (id >= 0) return byId(context, id);
                }
            } catch (RuntimeException notFound) {
                // Not a media file, or the provider does not say where it is.
            }
            return null;
        }
        // A file manager's own provider: the path if it gives one (column or URI), else the file's name and size.
        Uri item = byPath(context, dataColumn(context, uri));
        if (item == null) item = byPath(context, pathInUri(uri));
        if (item == null) item = byNameAndSize(context, uri);
        return item;
    }

    /** Who sent the URI, for the log (the provider, or the scheme of a file URI); never the file's name. */
    private static String source(Uri uri) {
        return uri.getAuthority() != null ? uri.getAuthority() : uri.getScheme() + ":";
    }

    private static boolean isMediaStore(Uri uri) {
        String authority = uri.getAuthority();
        return "content".equals(uri.getScheme()) && authority != null
                && (MediaStore.AUTHORITY.equals(authority) || authority.endsWith("@" + MediaStore.AUTHORITY));
    }

    private static boolean isDocument(Context context, Uri uri) {
        try {
            return DocumentsContract.isDocumentUri(context, uri);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Android's three storage document providers: the document id names the file (or its MediaStore id). */
    private static Uri byDocument(Context context, Uri uri) {
        String id = DocumentsContract.getDocumentId(uri);
        if (id == null) return null;
        int colon = id.indexOf(':');
        String kind = colon < 0 ? "" : id.substring(0, colon);
        String rest = colon < 0 ? id : id.substring(colon + 1);
        switch (uri.getAuthority()) {
            case EXTERNAL_STORAGE_DOCUMENTS:
                if (colon < 0) return null;
                File root = volumeRoot(context, kind);
                return root == null ? null : byPath(context, new File(root, rest).getPath());
            case MEDIA_DOCUMENTS:
                if (!"image".equals(kind) && !"video".equals(kind)) return null;
                try {
                    return byId(context, Long.parseLong(rest));
                } catch (NumberFormatException e) {
                    return null;
                }
            case DOWNLOAD_DOCUMENTS:
                if ("raw".equals(kind)) return byPath(context, rest);
                if ("msf".equals(kind)) {
                    try {
                        return byId(context, Long.parseLong(rest));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /** The folder an external storage document id starts at: primary, home (Documents) or a card's volume id. */
    private static File volumeRoot(Context context, String volume) {
        if ("primary".equalsIgnoreCase(volume)) return Environment.getExternalStorageDirectory();
        if ("home".equalsIgnoreCase(volume)) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        }
        StorageManager storage = context.getSystemService(StorageManager.class);
        if (storage != null) {
            for (StorageVolume v : storage.getStorageVolumes()) {
                if (volume.equalsIgnoreCase(v.getUuid()) && v.getDirectory() != null) return v.getDirectory();
            }
        }
        return new File("/storage/" + volume);
    }

    private static String dataColumn(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException noSuchColumn) {
            // Most providers only answer the openable columns.
        }
        return null;
    }

    /** A path in a provider's URI: content://pkg.provider/file%3A%2F%2F%2Fstorage..., .../root/storage/..., etc. */
    private static String pathInUri(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        int file = path.indexOf("file://");
        if (file >= 0) return Uri.parse(path.substring(file)).getPath();
        String storage = Environment.getExternalStorageDirectory().getPath();
        for (String root : new String[]{storage + "/", "/storage/", "/sdcard/"}) {
            int at = path.indexOf(root);
            if (at >= 0) return path.substring(at);
        }
        return null;
    }

    private static Uri byNameAndSize(Context context, Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            name = c.getString(0);
            if (!c.isNull(1)) size = c.getLong(1);
        } catch (RuntimeException e) {
            return null;
        }
        if (name == null || size < 0) return null;
        // Only a single match counts: copies of the same file in two folders are not told apart.
        return single(context, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=?",
                new String[]{name, Long.toString(size)});
    }

    private static Uri byPath(Context context, String path) {
        if (path == null || path.isEmpty()) return null;
        String canonical = path;
        try {
            canonical = new File(path).getCanonicalPath();
        } catch (Exception ignored) {
            // Keep the path as given.
        }
        Uri item = single(context, MediaStore.MediaColumns.DATA + "=?", new String[]{canonical});
        if (item == null && !canonical.equals(path)) item = single(context, MediaStore.MediaColumns.DATA + "=?", new String[]{path});
        return item;
    }

    private static Uri byId(Context context, long id) {
        return single(context, MediaStore.MediaColumns._ID + "=" + id, null);
    }

    /** The photo or video the selection finds, if exactly one. */
    private static Uri single(Context context, String selection, String[] args) {
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String where = "(" + selection + ") AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + "," + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        try (Cursor c = context.getContentResolver().query(files, new String[]{
                MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE}, where, args, null)) {
            if (c == null || c.getCount() != 1 || !c.moveToFirst()) return null;
            long id = c.getLong(0);
            Uri base = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            return ContentUris.withAppendedId(base, id);
        } catch (RuntimeException e) {
            Log.w(TAG, "open in album: " + e);
            return null;
        }
    }
}
