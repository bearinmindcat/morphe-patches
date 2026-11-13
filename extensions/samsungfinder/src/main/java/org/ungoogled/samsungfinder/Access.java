package org.ungoogled.samsungfinder;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * On One UI Finder is a system app that may read contacts without asking; elsewhere that is the user's choice. The
 * search screen asks once, the first time it opens; Android's app settings change it later.
 */
public final class Access {
    private static final int REQUEST = 0x5f1d;

    private Access() {
    }

    public static void ask(Activity activity) {
        try {
            if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return;
            SharedPreferences prefs = activity.getSharedPreferences("ungoogled_finder", 0);
            if (prefs.getBoolean("contacts_asked", false)) return;
            prefs.edit().putBoolean("contacts_asked", true).apply();
            activity.requestPermissions(new String[] {Manifest.permission.READ_CONTACTS}, REQUEST);
        } catch (RuntimeException e) {
            // Asked again next time.
        }
    }

    /**
     * Finder's "Show Finder on Apps screen" switch: on One UI the home screen opens Finder; elsewhere the Apps screen
     * icon is the only way in, so the switch is hidden.
     */
    public static void hideIconSwitch(Object preference) {
        if (preference == null) return;
        try {
            preference.getClass().getMethod("setVisible", boolean.class).invoke(preference, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Left visible.
        }
    }
}
