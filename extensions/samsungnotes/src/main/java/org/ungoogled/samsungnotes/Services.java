package org.ungoogled.samsungnotes;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Notes' background services (saving a note's document, collecting its text for search) off One UI. One UI keeps
 * Notes' process important while they run; elsewhere they run as plain services (see the patch), and Android refuses
 * to start one while the app is in the background: that start is skipped, as the work is picked up again later.
 */
public final class Services {
    private static final String TAG = "UA";

    private Services() {
    }

    /** Context.startService for Notes' service manager: null instead of a crash when Android refuses it. */
    public static ComponentName startService(Context context, Intent intent) {
        try {
            return context.startService(intent);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Samsung Notes service not started: " + e);
            return null;
        }
    }
}
