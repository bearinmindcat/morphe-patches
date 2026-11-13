package org.ungoogled.samsungcapture;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * Options only Samsung's preinstalled copy can carry out (unless this copy is installed as the system app itself, see
 * LocalSettings.systemCopy).
 * - The screenshot options (toolbar after a screenshot, hide status and navigation bars, delete shared screenshots,
 *   save originals, format, folder), hidden: One UI's SystemUI reads them when it takes a screenshot. Off One UI
 *   nothing does, and on a Galaxy phone they belong to Samsung's own copy (this copy keeps its own, see LocalSettings).
 * - "Show taps and touches": One UI draws the touches through a hidden InputManager call only Samsung's system app
 *   may make, and an app may not change Android's own Show taps (a private System setting) nor see touches outside its
 *   own windows. Android's Show taps is recorded, so the switch (settings and the recorder's sound question) shows
 *   Android's setting and opens it in Developer options; Samsung's own option is left as it is.
 */
public final class SystemOnly {
    private static final String TAG = "UA";
    private static final String[] PREFERENCES = {
        "key_category_screenshots",
        "key_screenshot_toolbar",
        "key_delete_shared_screenshots",
        "key_hide_status_and_navigation_bars",
        "key_save_original_screenshots",
        "key_screenshot_format",
        "key_screenshot_folder_picker",
    };
    private static final String SHOW_TAPS = "key_show_taps_and_touches";
    /** Settings.System.SHOW_TOUCHES (hidden, readable by apps); also the key of its row in Developer options. */
    private static final String SHOW_TOUCHES = "show_touches";
    private static final String FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    private static final String FRAGMENT_ARGS = ":settings:show_fragment_args";

    private SystemOnly() {
    }

    /** SettingsFragment.onCreate, after the options are read from their XML. */
    public static void hidePreferences(Object fragment) {
        if (LocalSettings.systemCopy()) return;
        try {
            Method find = fragment.getClass().getMethod("findPreference", CharSequence.class);
            Method setVisible = null;
            for (String key : PREFERENCES) {
                Object preference = find.invoke(fragment, key);
                if (preference == null) continue;
                if (setVisible == null) setVisible = preference.getClass().getMethod("setVisible", boolean.class);
                setVisible.invoke(preference, false);
            }
            Object showTaps = find.invoke(fragment, SHOW_TAPS);
            if (showTaps != null) {
                showTaps.getClass().getMethod("setSummary", CharSequence.class).invoke(showTaps, "Uses Android's Show taps");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Samsung capture: system-only options not set up", e);
        }
    }

    /** The stored "Show taps and touches" about to be shown on a switch: this copy shows Android's Show taps. */
    public static boolean showTaps(boolean stored) {
        return LocalSettings.systemCopy() ? stored : androidShowTaps();
    }

    /**
     * SettingsFragment.onPreferenceChange: true for "Show taps and touches" in this copy, which then opens Android's
     * Show taps instead and keeps the switch as it is (the screen shows Android's setting again when it comes back).
     */
    public static boolean preferenceChange(String key) {
        if (!SHOW_TAPS.equals(key) || LocalSettings.systemCopy()) return false;
        Context context = LocalSettings.context();
        if (context != null) openShowTaps(context);
        return true;
    }

    /**
     * The recorder's sound question, its "Show taps and touches" switch flipped: true in this copy, which puts the
     * switch back to Android's setting (inside the listener, so no second call), opens Android's Show taps and closes
     * the question as Cancel does (it would cover Developer options); a new recording asks again.
     */
    public static boolean showTapsSwitched(CompoundButton button, boolean checked, Object question) {
        if (LocalSettings.systemCopy()) return false;
        boolean actual = androidShowTaps();
        if (checked != actual) button.setChecked(actual);
        if (openShowTaps(button.getContext()) && question instanceof Dialog) ((Dialog) question).cancel();
        return true;
    }

    private static boolean androidShowTaps() {
        Context context = LocalSettings.context();
        if (context == null) return false;
        try {
            return Settings.System.getInt(context.getContentResolver(), SHOW_TOUCHES, 0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Samsung capture: Show taps not readable", e);
            return false;
        }
    }

    /** Developer options at Show taps, with a hint; only the hint when Developer options are off or missing. */
    private static boolean openShowTaps(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean developer;
        try {
            developer = Settings.Global.getInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (RuntimeException e) {
            developer = false;
        }
        if (developer) {
            Bundle args = new Bundle();
            args.putString(FRAGMENT_ARG_KEY, SHOW_TOUCHES);
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(FRAGMENT_ARG_KEY, SHOW_TOUCHES)
                .putExtra(FRAGMENT_ARGS, args);
            try {
                app.startActivity(intent);
                Toast.makeText(app, "Turn on Show taps here", Toast.LENGTH_LONG).show();
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Samsung capture: no Developer options", e);
            }
        }
        Toast.makeText(app, "Turn on Developer options > Show taps", Toast.LENGTH_LONG).show();
        return false;
    }
}
