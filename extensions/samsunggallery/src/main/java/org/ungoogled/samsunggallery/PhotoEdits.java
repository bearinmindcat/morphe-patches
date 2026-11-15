package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Edit on a photo or GIF in Gallery opens Samsung's Photo Editor (com.sec.android.mimage.photoretouching), as on One
 * UI. Where it is not installed, Gallery offered to download it from the Galaxy Store, which off One UI only says
 * "Can't download right now": the picture goes to the phone's own photo editors (Android's "Edit"), or a message says
 * none is installed.
 */
public final class PhotoEdits {
    private static final String TAG = "UA";
    private static final ComponentName PHOTO_EDITOR = new ComponentName(
            "com.sec.android.mimage.photoretouching", "com.sec.android.mimage.photoretouching.SPEActivity");

    /** The photo Gallery is handing to Photo Editor (see launchFailed). */
    private static volatile Uri launching;

    private PhotoEdits() {
    }

    /** Edit on a photo: true when the photo went elsewhere (Photo Editor is not installed); Gallery's launch stops. */
    public static boolean openElsewhere(Activity activity, Uri uri) {
        if (activity == null || uri == null) return false;
        if (resolves(activity, new Intent().setComponent(PHOTO_EDITOR))) {
            launching = uri;
            return false;
        }
        return edit(activity, uri);
    }

    /**
     * Gallery's launch of Photo Editor failed after all (ActivityNotFoundException): the photo goes to Android's
     * "Edit" too, where Gallery offered the Galaxy Store.
     */
    public static void launchFailed(Activity activity) {
        Uri uri = launching;
        launching = null;
        if (activity != null && uri != null) edit(activity, uri);
    }

    /**
     * Edit on a GIF: Gallery's {@code activity.startActivity(intent)} for Photo Editor's GIF editor, which off One UI
     * closes at once (it decodes GIFs with One UI's decoder). The GIF (the intent's "baseImage") goes to Android's
     * "Edit" instead, where Photo Editor is one of the choices when installed.
     */
    public static void startGifEditor(Activity activity, Intent intent) {
        Uri uri = null;
        try {
            uri = intent.getParcelableExtra("baseImage");
        } catch (RuntimeException ignored) {
            // Leave it to Gallery.
        }
        if (uri == null || !edit(activity, uri)) activity.startActivity(intent);
    }

    private static boolean resolves(Activity activity, Intent intent) {
        try {
            return activity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (RuntimeException e) {
            return true; // Leave it to Gallery.
        }
    }

    /** Android's "Edit" with the picture, readable only: editors save their result as a copy. */
    private static boolean edit(Activity activity, Uri uri) {
        String type = null;
        try {
            type = activity.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // Fall back to any image.
        }
        Intent edit = new Intent(Intent.ACTION_EDIT)
                .setDataAndType(uri, type != null && type.startsWith("image/") ? type : "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        StoreGuides.fellBack();
        try {
            activity.startActivity(edit);
        } catch (RuntimeException e) {
            Log.w(TAG, "no photo editor: " + e);
            Toast.makeText(activity, VideoEdits.noEditorMessage(activity), Toast.LENGTH_SHORT).show();
        }
        return true;
    }
}
