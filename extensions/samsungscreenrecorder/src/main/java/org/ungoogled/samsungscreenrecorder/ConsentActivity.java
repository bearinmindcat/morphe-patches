package org.ungoogled.samsungscreenrecorder;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Asks for what a recording needs and One UI grants Screen Recorder without asking: the runtime permissions
 * (EXTRA_PERMISSIONS, then opens EXTRA_THEN again) or, for one recording, Android's screen capture consent (its Start
 * starts EXTRA_SERVICE, the recording service). See Projection.
 */
public final class ConsentActivity extends Activity {
    static final String EXTRA_PERMISSIONS = "org.ungoogled.samsungscreenrecorder.PERMISSIONS";
    static final String EXTRA_THEN = "org.ungoogled.samsungscreenrecorder.THEN";
    static final String EXTRA_SERVICE = "org.ungoogled.samsungscreenrecorder.SERVICE";
    /** The recorder's own channel (MediaUtil.createNotificationChannelAndGetChannelId). */
    private static final String RECORDING_CHANNEL = "CHANNEL_ID_RECORDING_SCREEN";
    private static final int PERMISSIONS = 1;
    private static final int CAPTURE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        notificationChannel();
        String[] permissions = getIntent().getStringArrayExtra(EXTRA_PERMISSIONS);
        if (permissions != null) {
            requestPermissions(permissions, PERMISSIONS);
            return;
        }
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "no screen capture dialog", e);
            finish();
        }
    }

    /**
     * The recording notification's channel, made while this screen shows: an app made for Android 9 gets Android's
     * notification question only then, and without notifications the recording and the saved video show no notification.
     */
    private void notificationChannel() {
        int name = getResources().getIdentifier("record_screen", "string", getPackageName());
        if (name == 0) return;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(RECORDING_CHANNEL, getString(name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSIONS) return;
        boolean granted = results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Intent then = getIntent().getParcelableExtra(EXTRA_THEN);
            if (then != null) startActivity(then);
        } else {
            int text = getResources().getIdentifier("grant_permission_and_try_again", "string", getPackageName());
            if (text != 0) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Intent service = getIntent().getParcelableExtra(EXTRA_SERVICE);
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null && service != null) {
            Projection.granted(this, resultCode, data, service);
        }
        finish();
    }
}
