package org.ungoogled.samsungcapture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Samsung's screenshot editor (crop, pen, highlighter, text; ScreenWriterActivity) for any picture. On One UI it opens
 * from the toolbar SystemUI shows after a screenshot; elsewhere SystemUI never shows that toolbar, so the editor opens
 * from "Edit" or "Share" in other apps, or from the launcher shortcut (which picks a picture first). It edits a copy in
 * the app's cache and saves the result to the screenshots folder, as on One UI.
 */
public final class EditActivity extends Activity {
    private static final String EDITOR = "com.samsung.android.app.smartcapture.screenwriter.ScreenWriterActivity";
    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        Uri image = image(getIntent());
        if (image != null) {
            open(image);
            return;
        }
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "no picture picker", e);
            finish();
        }
    }

    private static Uri image(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return intent.getData();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            open(data.getData());
            return;
        }
        finish();
    }

    private void open(Uri image) {
        File copy = copy(image);
        if (copy == null) {
            int text = getResources().getIdentifier("not_support_msg", "string", getPackageName());
            if (text != 0) Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            // The editor opens with an animation from the screenshot toolbar's thumbnail and stays hidden without
            // one; here it grows from the middle of the screen.
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int w = metrics.widthPixels, h = metrics.heightPixels;
            startActivity(new Intent()
                    .setClassName(this, EDITOR)
                    .putExtra("savepath", copy.getPath())
                    .putExtra("thumbnailBounds", new android.graphics.Rect(w * 3 / 8, h * 3 / 8, w * 5 / 8, h * 5 / 8))
                    .putExtra("thumbnailRadius", 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "editor not opened", e);
        }
        finish();
    }

    /** The picture as a file the editor can read (it works on paths), in the cache; older copies go. */
    private File copy(Uri image) {
        File dir = new File(getCacheDir(), "edit");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String type = getContentResolver().getType(image);
        String extension = type != null && type.contains("jpeg") ? ".jpg" : type != null && type.contains("webp") ? ".webp" : ".png";
        File file = new File(dir, "Edit_" + System.currentTimeMillis() + extension);
        try (InputStream in = getContentResolver().openInputStream(image); OutputStream out = new FileOutputStream(file)) {
            if (in == null) return null;
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return file;
        } catch (Exception e) {
            Log.w(Projection.TAG, "picture not readable: " + image, e);
            file.delete();
            return null;
        }
    }
}
