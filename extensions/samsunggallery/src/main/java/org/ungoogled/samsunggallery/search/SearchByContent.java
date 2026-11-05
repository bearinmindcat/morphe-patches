package org.ungoogled.samsunggallery.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.ungoogled.samsunggallery.ActivityState;

/**
 * "Search by content": Gallery's search and its Things/Scenery and Documents categories, filled by an on-device
 * image model instead of One UI's system apps (which write Samsung's media tables; off One UI those are empty).
 * Nothing is downloaded or looked at before the user allows it, once, when the search page first shows its
 * categories; "Don't allow" leaves search as it was. Search settings has a row to see progress and turn it on or off.
 */
public final class SearchByContent {
    private static final String TAG = "UA";
    private static final String PREFS = "ungoogled_search_by_content";
    private static final String CONSENT = "consent";
    private static final String ALLOWED = "allowed";
    private static final String DENIED = "denied";
    private static final String FAILURES = "download_failures";
    /** Downloads that arrive but do not match their SHA-256, before giving up (turning it off and on tries again). */
    private static final int MAX_FAILURES = 3;

    private static volatile Context app;
    private static volatile boolean asked;

    private SearchByContent() {
    }

    /** Whether the "Search by content" patch is in (it makes this return true; the extension is shared). */
    private static boolean patched() {
        return false;
    }

    /** Gallery's SQL, adjusted for what Search by content writes (see SearchIndex.wholeWords). */
    public static String query(String sql) {
        return patched() ? SearchIndex.wholeWords(sql) : sql;
    }

    /** Gallery's process started (the media copy's provider): the work the user allowed goes on. */
    public static void start(Context context) {
        if (!patched()) return;
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!allowed(app)) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> IndexJob.schedule(app), 5000);
    }

    /** Gallery queried its scene categories (the search page): asks once whether to download the model. */
    public static void categoriesShown() {
        Context c = app;
        if (c == null || asked || consent(c) != null) return;
        asked = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Activity activity = ActivityState.front();
            if (activity == null || activity.isFinishing() || consent(activity) != null) {
                asked = false;
                return;
            }
            ask(activity, null);
        }, 600);
    }

    /** The one-time question; done runs after the answer (on the main thread). */
    static void ask(Activity activity, Runnable done) {
        try {
            dialog(activity)
                    .setTitle("Search by content")
                    .setMessage("Find photos by what's in them, such as dogs, food or credit cards. This needs a "
                            + "one-time download of an image recognition model (about " + SearchModel.MEGABYTES
                            + " MB, over Wi-Fi). Photos are looked at on this phone, mostly while it charges.")
                    .setPositiveButton("Allow", (d, w) -> allow(activity))
                    .setNegativeButton("Don't allow", (d, w) -> prefs(activity).edit().putString(CONSENT, DENIED).apply())
                    .setOnDismissListener(d -> {
                        if (done != null) done.run();
                    })
                    .show();
        } catch (RuntimeException e) {
            Log.w(TAG, "search: not asked: " + e);
        }
    }

    static AlertDialog.Builder dialog(Activity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return new AlertDialog.Builder(activity, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    static void allow(Context context) {
        prefs(context).edit().putString(CONSENT, ALLOWED).putInt(FAILURES, 0).apply();
        Log.i(TAG, "search: allowed");
        IndexJob.schedule(context);
    }

    /** Turned off: no more work, the model and what was found are deleted. */
    static void turnOff(Context context) {
        prefs(context).edit().putString(CONSENT, DENIED).apply();
        IndexJob.cancel(context);
        new Thread(() -> {
            SearchModel.delete(context);
            SearchIndex.clear();
            Log.i(TAG, "search: turned off, model and tags removed");
        }, "UA-search-off").start();
    }

    static boolean allowed(Context context) {
        return ALLOWED.equals(consent(context));
    }

    /** null until the user answered. */
    static String consent(Context context) {
        return prefs(context).getString(CONSENT, null);
    }

    static boolean gaveUp(Context context) {
        return prefs(context).getInt(FAILURES, 0) >= MAX_FAILURES;
    }

    /** After a download job: true to try again later. */
    static boolean downloadDone(Context context, boolean ok) {
        if (ok) {
            prefs(context).edit().putInt(FAILURES, 0).apply();
            return false;
        }
        int failures = prefs(context).getInt(FAILURES, 0) + 1;
        prefs(context).edit().putInt(FAILURES, failures).apply();
        return failures < MAX_FAILURES;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
