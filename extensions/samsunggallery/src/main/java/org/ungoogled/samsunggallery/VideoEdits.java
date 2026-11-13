package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * A video's Edit in Gallery opens Samsung's Editor Lite (com.samsung.app.newtrim) where it is installed, as on One UI.
 * Where it is not, Gallery only logged the missing app and Edit did nothing: the video goes to the phone's own video
 * editors (Android's "Edit" with the video), or a message says none is installed.
 */
public final class VideoEdits {
    private static final String TAG = "UA";
    private static final ComponentName EDITOR_LITE =
            new ComponentName("com.samsung.app.newtrim", "com.samsung.app.newtrim.trimactivity.TrimActivity");

    private VideoEdits() {
    }

    /** True when the video went elsewhere (Editor Lite is not installed); Gallery's own launch then stops. */
    public static boolean openElsewhere(Activity activity, Uri uri) {
        if (activity == null || uri == null) return false;
        Intent lite = new Intent(Intent.ACTION_MAIN).setComponent(EDITOR_LITE);
        try {
            if (activity.getPackageManager().resolveActivity(lite, PackageManager.MATCH_DEFAULT_ONLY) != null) return false;
        } catch (RuntimeException e) {
            return false;
        }
        String type = null;
        try {
            type = activity.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // Fall back to any video.
        }
        Intent edit = new Intent(Intent.ACTION_EDIT)
                .setDataAndType(uri, type != null && type.startsWith("video/") ? type : "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(edit);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "no video editor: " + e);
            Toast.makeText(activity, noEditorMessage(activity), Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** Gallery's own "No apps available to complete this action." */
    private static CharSequence noEditorMessage(Context context) {
        int id = context.getResources().getIdentifier("activity_not_found", "string", context.getPackageName());
        return id != 0 ? context.getText(id) : "No apps available to complete this action.";
    }
}
