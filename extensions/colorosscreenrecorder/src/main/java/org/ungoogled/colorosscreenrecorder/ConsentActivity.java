package org.ungoogled.colorosscreenrecorder;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Android's screen capture dialog, shown for one recording (see Projection). */
public final class ConsentActivity extends Activity {
    static final String EXTRA_REQUEST = "org.ungoogled.colorosscreenrecorder.REQUEST";
    private static final int CAPTURE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
        } catch (RuntimeException e) {
            android.util.Log.w("ScreenRecorderPort", "no screen capture dialog", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) {
            try {
                Projection.granted(this, data, getIntent().getBundleExtra(EXTRA_REQUEST));
            } catch (RuntimeException e) {
                android.util.Log.w("ScreenRecorderPort", "recording not started", e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
