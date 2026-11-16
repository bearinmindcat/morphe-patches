package org.ungoogled.ui;

import android.app.LocaleManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.LocaleList;

/**
 * Issue #198: the app language chosen in Maps (Android's per-app language) went back to the system language after
 * a reboot. Android keeps an app's own language and own night mode in one record and saves it to disk only partly:
 * once the record has a night mode, every later save writes the night mode alone (the locales are skipped), so the
 * language is in effect until the next boot only. Maps set an own night mode whenever its theme is Light or Dark --
 * always with Black theme, which pins Maps to dark.
 *
 * Maps' theme now goes through AppCompat (as on Android 11 and older) instead of Android's per-app night mode, and,
 * once, the record a previous version left is cleared and the language saved again on its own.
 */
public final class AppLanguage {
    private AppLanguage() {}

    /** Set at patch time when Maps' theme no longer uses Android's per-app night mode. */
    static boolean patched() { return false; }

    private static final String PREFS = "ungoogled_app_language";
    private static final String KEY_CLEARED = "night_record_cleared";

    public static void processStart(Context c) {
        try {
            if (!patched() || Build.VERSION.SDK_INT < 31) return;
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean(KEY_CLEARED, false)) return;
            UiModeManager ui = c.getSystemService(UiModeManager.class);
            LocaleManager lm = Build.VERSION.SDK_INT >= 33 ? c.getSystemService(LocaleManager.class) : null;
            LocaleList chosen = lm != null ? lm.getApplicationLocales() : null;
            // Night mode back to "none"; with no language either the record is dropped, else drop it by clearing the
            // language for a moment and save the language again into a fresh record.
            if (ui != null) ui.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO);
            if (lm != null && chosen != null && !chosen.isEmpty()) {
                lm.setApplicationLocales(LocaleList.getEmptyLocaleList());
                lm.setApplicationLocales(chosen);
            }
            p.edit().putBoolean(KEY_CLEARED, true).apply();
        } catch (Throwable t) {
            android.util.Log.w("UA", "app language", t);
        }
    }
}
