package org.ungoogled.nothingscreenshot;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On Nothing OS the editor replaces the screenshot it was opened on: saving an edit deletes the original. Here it edits
 * any image another app hands it (a photo from the gallery, a file), so saving deletes only an image it saved itself
 * (an earlier save of the same edit); everything else is kept and the edit is saved as a new image. The editor's own
 * Delete button (which asks first) still deletes the image it shows.
 */
public final class Screenshots {
    private static final String TAG = "UA";

    /** Set by a save right before it deletes the image it replaces; the delete that follows reads it. */
    private static final AtomicBoolean SAVING = new AtomicBoolean();

    private Screenshots() {
    }

    /** From the save, right before it deletes the image the edit replaces. */
    public static void saving() {
        SAVING.set(true);
    }

    /** True if this delete is a save's and [uri] is not an image this app saved itself: then the image is kept. */
    public static boolean keep(Context context, Uri uri) {
        if (!SAVING.getAndSet(false)) return false;
        boolean own = own(context, uri);
        if (!own) Log.i(TAG, "screenshot: the original is kept, the edit is saved as a new image");
        return !own;
    }

    private static boolean own(Context context, Uri uri) {
        if (context == null || uri == null || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return false;
        try (Cursor c = context.getContentResolver().query(uri, new String[] {MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
                null, null, null)) {
            return c != null && c.moveToFirst() && context.getPackageName().equals(c.getString(0));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
