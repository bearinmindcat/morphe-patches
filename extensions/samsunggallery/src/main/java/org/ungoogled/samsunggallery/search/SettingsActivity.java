package org.ungoogled.samsunggallery.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Search settings' "Search by content" row: how far it got, and on or off. A dialog over Gallery's settings. */
public final class SettingsActivity extends Activity {
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        show();
    }

    private void show() {
        if (!SearchByContent.allowed(this)) {
            SearchByContent.ask(this, this::finish);
            return;
        }
        dialog = SearchByContent.dialog(this)
                .setTitle("Search by content")
                .setMessage("…")
                .setPositiveButton("Close", null)
                .setNegativeButton("Turn off", (d, w) -> SearchByContent.turnOff(this))
                .setOnDismissListener(d -> finish())
                .show();
        new Thread(this::status, "UA-search-status").start();
    }

    /** The status line, read off the main thread (MediaStore and the saved database). */
    private void status() {
        String text;
        if (!SearchModel.ready(this)) {
            long have = SearchModel.downloaded(this);
            if (SearchByContent.gaveUp(this)) {
                text = "The model didn't download. Turn it off and on to try again.";
            } else if (have > 0) {
                text = "Downloading the model: " + (have * 100 / SearchModel.size()) + "%.";
            } else {
                text = "The model (" + SearchModel.MEGABYTES + " MB) downloads when the phone is on Wi-Fi.";
            }
        } else {
            int[] p = SearchIndex.progress(this);
            text = p[0] >= p[1] ? "All " + p[1] + " photos and videos are looked at."
                    : p[0] + " of " + p[1] + " photos and videos looked at. The rest follow, mostly while the phone charges.";
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (dialog != null && dialog.isShowing()) dialog.setMessage(text);
        });
    }
}
