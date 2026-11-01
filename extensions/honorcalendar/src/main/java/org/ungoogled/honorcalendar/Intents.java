package org.ungoogled.honorcalendar;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * MagicOS screens Calendar opens that nothing answers elsewhere, given Android's own instead when nothing does: the
 * reminder settings' "Notifications" (MagicOS's notification centre) -> Android's notification settings for Calendar;
 * the time zone picker (MagicOS Settings' zone picker) -> ZonePicker.
 */
public final class Intents {
    static final String NOTIFICATION_SETTINGS = "hihonor.intent.action.NOTIFICATIONSETTING";
    static final String ZONE_PICKER = "hihonor.intent.action.ZONE_PICKER";

    private Intents() {
    }

    public static void fit(Context context, Intent intent) {
        try {
            if (intent == null) return;
            if (context == null) context = Calendars.context();
            String action = intent.getAction();
            if (action == null || intent.resolveActivity(context.getPackageManager()) != null) return;
            switch (action) {
                case NOTIFICATION_SETTINGS:
                    intent.setComponent(null);
                    intent.setPackage(null);
                    intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                    break;
                case ZONE_PICKER:
                    intent.setPackage(null);
                    intent.setClassName(context.getPackageName(), ZonePicker.class.getName());
                    break;
            }
        } catch (RuntimeException e) {
            android.util.Log.w("UA", "intent " + intent + ": " + e);
        }
    }

    /** The zone picker intent Calendar made (it has no Context at hand). */
    public static android.content.Intent fit(Intent intent) {
        fit(null, intent);
        return intent;
    }
}
