package org.ungoogled.ui;

import android.service.notification.NotificationListenerService;

/**
 * Media playing elsewhere: Android lists its active media sessions only to an app holding notification access,
 * which needs a listener like this one. It reads no notification and lets the system unbind it straight away;
 * the access stays granted.
 */
public final class MediaListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        try {
            requestUnbind();
        } catch (Throwable ignored) {
        }
    }
}
