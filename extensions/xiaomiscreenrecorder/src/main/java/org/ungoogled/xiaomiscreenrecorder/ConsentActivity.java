package org.ungoogled.xiaomiscreenrecorder;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Android's screen capture dialog, shown for one recording (see Projection). Opened with EXTRA_CLOSE_SHADE it only
 * lets the Screen Recorder tile close the notification shade (see Panels) and closes at once.
 */
public final class ConsentActivity extends Activity {
    static final String EXTRA_CLOSE_SHADE = "org.ungoogled.xiaomiscreenrecorder.CLOSE_SHADE";
    private static final int CAPTURE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_CLOSE_SHADE, false)) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
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
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) Projection.granted(this, data);
        finish();
    }
}
