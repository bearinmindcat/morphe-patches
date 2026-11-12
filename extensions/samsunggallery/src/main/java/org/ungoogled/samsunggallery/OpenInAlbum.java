package org.ungoogled.samsunggallery;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A photo opened from a camera app showed alone: Gallery opens a photo within its album (swipe to the ones around it)
 * only when the intent names the album -- Samsung's camera passes its own extras, Samsung's My Files the photo's path --
 * and it does not handle the camera's REVIEW action at all. Other cameras (GCam, and GCam through a "Photos" stub that
 * turns REVIEW into VIEW) send only the photo's MediaStore URI. A REVIEW of a photo, or a VIEW of a photo in a camera
 * folder (DCIM), now names the photo's album (bucketId, the extra My Files uses), so Gallery's own album viewer opens.
 */
public final class OpenInAlbum {
    private static final String TAG = "UA";
    private static final String CAMERA_REVIEW = "com.android.camera.action.REVIEW";
    private static final String PROVIDER_REVIEW = "android.provider.action.REVIEW";
    /** Gallery's extras naming an album or a camera hand-off: Gallery already knows what to show. */
    private static final String[] ALBUM_EXTRAS = {"bucketId", "bucketIds", "AbsolutePath", "from-Camera", "viewbuckets"};
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
            if (uri == null || !"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return;
            String type = intent.getType();
            if (type != null && type.startsWith("vnd.android.cursor.dir")) return;
            for (String extra : ALBUM_EXTRAS) {
                if (intent.hasExtra(extra)) return;
            }
            try {
                if (ContentUris.parseId(uri) < 0) return;
            } catch (RuntimeException notAnItem) {
                return;
            }
            Context context = MediaMirror.context();
            if (context == null) return;
            try (Cursor c = context.getContentResolver().query(uri, new String[]{
                    MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                if (c == null || !c.moveToFirst() || c.isNull(0)) return;
                int bucket = (int) c.getLong(0);
                String folder = c.getString(1);
                boolean camera = folder != null && folder.regionMatches(true, 0, "DCIM/", 0, 5);
                if (bucket == 0 || (!review && !camera)) return;
                intent.putExtra("bucketId", bucket);
                // Gallery waits only 2 s for the photo's row in the album; on a cold start the copy is made first.
                MediaMirror.warmUp();
                Log.i(TAG, "open in album: " + uri + " in " + folder + (review ? " (camera review)" : ""));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "open in album: " + e);
        }
    }
}
