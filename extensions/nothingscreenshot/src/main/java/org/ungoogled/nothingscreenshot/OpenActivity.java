package org.ungoogled.nothingscreenshot;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

/**
 * Nothing Screenshot's ways in off Nothing OS. There its editor opens only from Nothing's screenshot preview (an
 * action and a permission only Nothing's own system apps have); here this screen opens it: from the launcher it lets
 * the user pick an image first, and other apps' "Edit" (an image's ACTION_EDIT) hands it the image directly.
 */
public final class OpenActivity extends Activity {
    private static final String TAG = "UA";
    private static final String EDIT = "nothing.intent.action.NT_SCREENSHOT_EDIT";
    private static final String EDITOR = "com.nothing.ntscreenshot.MainActivity";
    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Recreated while the picker was open: its answer comes to onActivityResult.
        if (saved != null) return;
        Uri image = image(getIntent());
        if (image != null) {
            edit(image, getIntent().getType());
        } else {
            pick();
        }
    }

    /** The image another app handed over (its data, or for a share its stream), or null to pick one. */
    private static Uri image(Intent intent) {
        if (intent == null || Intent.ACTION_MAIN.equals(intent.getAction())) return null;
        if (intent.getData() != null) return intent.getData();
        try {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (stream != null) return stream;
        } catch (RuntimeException e) {
            // Not a Uri: nothing handed over.
        }
        ClipData clip = intent.getClipData();
        return clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getUri() : null;
    }

    private void pick() {
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK);
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "screenshot: no photo picker, any file picker opens");
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to pick an image with.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            edit(data.getData(), data.getType());
        } else {
            finish();
        }
    }

    /** Opens the editor on the image, which may read it for as long as the editor is open. */
    private void edit(Uri image, String type) {
        Intent editor = new Intent(EDIT)
                .setDataAndType(image, type != null && type.startsWith("image/") ? type : "image/*")
                .setClassName(this, EDITOR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editor.setClipData(ClipData.newRawUri("", image));
        try {
            startActivity(editor);
        } catch (RuntimeException e) {
            Log.w(TAG, "screenshot: the editor did not open: " + e);
            Toast.makeText(this, "This image can't be opened.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
