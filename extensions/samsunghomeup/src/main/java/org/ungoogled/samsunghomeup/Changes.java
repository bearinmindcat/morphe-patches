package org.ungoogled.samsunghomeup;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;

/**
 * Home Up tells One UI Home about a changed setting with a content change notification. Android holds such a
 * notification back 10 seconds for an app that is not in the foreground. On a Samsung phone One UI Home is the
 * system's recents app and never held back; elsewhere it is an ordinary home app, so every change reached it 10
 * seconds late, and a backup ("Immediate") gave up after waiting 10 seconds for One UI Home's answer. The
 * notification is sent without the delay, as Android's own settings provider does.
 */
public final class Changes {
    /** ContentResolver.NOTIFY_NO_DELAY (hidden; ContentService accepts it from any app). */
    private static final int NOTIFY_NO_DELAY = 1 << 15;

    private Changes() {
    }

    public static void notifyChange(ContentResolver resolver, Uri uri, ContentObserver observer) {
        try {
            resolver.notifyChange(uri, observer, NOTIFY_NO_DELAY);
        } catch (RuntimeException refused) {
            resolver.notifyChange(uri, observer);
        }
    }
}
