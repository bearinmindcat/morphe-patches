package org.ungoogled.honornotes;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Settings' "Notifications" opens MagicOS's notification centre (hihonor.intent.action.NOTIFICATIONSETTING in Honor's
 * system manager); when nothing answers it, Android's notification settings for Notes.
 */
public final class Intents {
    static final String NOTIFICATION_SETTINGS = "hihonor.intent.action.NOTIFICATIONSETTING";

    private Intents() {
    }

    public static void fit(Context context, Intent intent) {
        try {
            if (context == null || intent == null || !NOTIFICATION_SETTINGS.equals(intent.getAction())) return;
            if (intent.resolveActivity(context.getPackageManager()) != null) return;
            intent.setComponent(null);
            intent.setPackage(null);
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } catch (RuntimeException e) {
            android.util.Log.w("UA", "intent " + intent + ": " + e);
        }
    }
}
