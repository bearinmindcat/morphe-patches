package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

/**
 * Shows Android's dialog for one MediaStore request (see MediaRequests) over Gallery, invisibly, and hands the answer
 * back. The first time a request would ask, it first explains media management access, once.
 */
public final class MediaRequestActivity extends Activity {
    static final String EXTRA_ID = "org.ungoogled.samsunggallery.MEDIA_REQUEST";
    private static final int ASK = 1;
    private static final int SETTINGS = 2;

    private MediaRequests.Request request;
    private int id;
    private boolean answered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getIntent().getIntExtra(EXTRA_ID, 0);
        request = MediaRequests.pending(id);
        if (request == null || savedInstanceState != null) {
            close(false);
            return;
        }
        if (request.explain && !MediaRequests.explained(this)) {
            explain();
        } else {
            ask();
        }
    }

    private void explain() {
        MediaRequests.setExplained(this);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        new AlertDialog.Builder(this, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Allow media management?")
                .setMessage("Android asks you to confirm each delete and restore. Allow Gallery to manage media to skip that.")
                .setPositiveButton("Settings", (d, w) -> openSettings())
                .setNegativeButton("Not now", (d, w) -> ask())
                .setOnCancelListener(d -> ask())
                .show();
    }

    private void openSettings() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + getPackageName())), SETTINGS);
        } catch (RuntimeException e) {
            Log.w(MediaRequests.TAG, "no media management settings: " + e);
            ask();
        }
    }

    private void ask() {
        try {
            IntentSender sender = request.intent.getIntentSender();
            startIntentSenderForResult(sender, ASK, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Log.w(MediaRequests.TAG, "media request not shown: " + e);
            close(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SETTINGS) {
            if (Log.isLoggable(MediaRequests.TAG, Log.DEBUG)) {
                Log.d(MediaRequests.TAG, "media management now " + MediaStore.canManageMedia(this));
            }
            ask();
        } else if (requestCode == ASK) {
            close(resultCode == RESULT_OK);
        }
    }

    private void close(boolean applied) {
        if (!answered) {
            answered = true;
            if (request != null) request.finish(applied);
            MediaRequests.forget(id);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!answered && isFinishing()) {
            answered = true;
            if (request != null) request.finish(false);
            MediaRequests.forget(id);
        }
    }
}
