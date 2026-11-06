package org.ungoogled.honorscreenrecorder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Android's screen capture dialog, shown for one recording (see Projection). The first time it also asks for the
 * notification permission (Android 13+), which the recorder declares but, as a system app on Honor phones, never asks
 * for: without it the recording notification, with its stop button, stays hidden.
 */
public final class ConsentActivity extends Activity {
    private static final int CAPTURE = 1;
    private static final int NOTIFICATIONS = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences asked = getSharedPreferences("ungoogled_honor", Context.MODE_PRIVATE);
            if (!asked.getBoolean("notifications_asked", false)) {
                asked.edit().putBoolean("notifications_asked", true).apply();
                requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, NOTIFICATIONS);
                return;
            }
        }
        capture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATIONS) capture();
    }

    private void capture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "no screen capture dialog", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) {
            Projection.granted(this, data);
        } else if (requestCode == CAPTURE && !Projection.recording()) {
            // Cancelled: the recording service started for it has nothing to do.
            try {
                stopService(new Intent().setClassName(this, Projection.SERVICE));
            } catch (RuntimeException e) {
                Log.w(Projection.TAG, "recording service not stopped", e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
