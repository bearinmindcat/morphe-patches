package org.ungoogled.samsungcalendar;

import android.net.Uri;
import android.provider.Settings;

/** Event alerts off One UI. */
public final class Alerts {
    private Alerts() {
    }

    /** The alert channel's sound: One UI's own sound where there is one, the phone's default notification sound elsewhere. */
    public static Uri sound(Uri samsung) {
        return samsung != null ? samsung : Settings.System.DEFAULT_NOTIFICATION_URI;
    }
}
