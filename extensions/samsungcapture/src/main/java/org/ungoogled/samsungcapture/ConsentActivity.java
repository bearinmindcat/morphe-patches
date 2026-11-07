package org.ungoogled.samsungcapture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * What a recording needs that One UI grants Samsung capture without asking: drawing over other apps (the recorder's
 * controls float over them), notifications (the recording's own) and, for each recording, Android's screen capture
 * consent, whose Start starts the recording service. See Projection.
 */
public final class ConsentActivity extends Activity {
    private static final int OVERLAY = 1;
    private static final int CAPTURE = 2;
    private static final int NOTIFICATIONS = 3;
    private boolean fromQuickPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        fromQuickPanel = getIntent().getBooleanExtra(Projection.FROM_QUICK_PANEL, false);
        if (!Settings.canDrawOverlays(this)) {
            text("ungoogled_overlay_needed");
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), OVERLAY);
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "no overlay settings screen", e);
                finish();
            }
            return;
        }
        notifications();
    }

    private void text(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        if (id != 0) Toast.makeText(this, id, Toast.LENGTH_LONG).show();
    }

    /** The recording's notification shows only with Android's notification permission; asked once, then the capture. */
    private void notifications() {
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("ungoogled", MODE_PRIVATE).getBoolean("notifications_asked", false)) {
            getSharedPreferences("ungoogled", MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATIONS);
            return;
        }
        capture();
    }

    private void capture() {
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "no screen capture dialog", e);
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATIONS) capture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                notifications();
            } else {
                finish();
            }
            return;
        }
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) {
            try {
                Projection.granted(this, resultCode, data, fromQuickPanel);
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "recording service not started", e);
            }
        }
        finish();
    }
}
