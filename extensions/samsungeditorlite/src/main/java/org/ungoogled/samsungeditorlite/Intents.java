package org.ungoogled.samsungeditorlite;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/**
 * Editor Lite reads the video from its own "uri" extra, which only Samsung Gallery sets. A video opened with any
 * app's "Edit" (Android's ACTION_EDIT, the video as the intent's data) gets the same extra, and Gallery's editing
 * flow (cut, then save next to the original).
 */
public final class Intents {
    private Intents() {
    }

    public static void adopt(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || intent.hasExtra("uri")) return;
        Uri data = intent.getData();
        if (data == null) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) data = (Uri) stream;
        }
        if (data == null) return;
        intent.putExtra("uri", data);
        if (!intent.hasExtra("CALLER_APP")) intent.putExtra("CALLER_APP", "easyshare");
    }
}
