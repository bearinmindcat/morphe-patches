package org.ungoogled.samsungweather;

import android.util.Log;

/**
 * Weather writes its forecast into Android's on-device search index (AppSearch) for One UI's Now Brief. Below
 * Android 15 the platform index cannot hold Weather's schema (it uses parent types), and below Android 12 there is no
 * index at all; the AppSearch library says so by throwing, which killed Weather on start. The patch routes the
 * library's session opening and schema setting through methods generated here at patch time (same shape as the
 * calls), which turn that refusal into the library's own failed result: Weather already handles that one by skipping
 * the write.
 */
public final class AppSearch {
    private static final String TAG = "UA";

    private AppSearch() {
    }

    /** Notes why the index was skipped and gives the message for the failed result. */
    public static String skipped(Throwable reason) {
        String message = String.valueOf(reason);
        Log.i(TAG, "Weather search index skipped: " + message);
        return message;
    }
}
