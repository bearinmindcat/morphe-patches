package org.ungoogled.samsungcapture;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

/**
 * Options only Samsung's preinstalled copy can carry out, hidden in this copy (unless it is installed as the system
 * app itself, see LocalSettings.systemCopy).
 * - The screenshot options (toolbar after a screenshot, hide status and navigation bars, delete shared screenshots,
 *   save originals, format, folder): One UI's SystemUI reads them when it takes a screenshot. Off One UI nothing does,
 *   and on a Galaxy phone they belong to Samsung's own copy (this copy keeps its own, see LocalSettings).
 * - "Show taps and touches": One UI draws the touches through a hidden InputManager call only Samsung's system app
 *   may make. An app gets touches only on its own windows (touches elsewhere go to the apps underneath, which is what
 *   keeps the screen usable while recording), so it can't draw them either; Android's own Developer options > Show
 *   taps does, and the recording shows it.
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
        "key_show_taps_and_touches",
    };
    private static final String SHOW_TAPS_SWITCH = "screen_recorder_show_taps_switch";
    private static int showTapsSwitch;

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
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Samsung capture: system-only options not hidden", e);
        }
    }

    /** View.findViewById in the recorder's sound question: its "Show taps and touches" switch stays hidden. */
    public static View findViewById(View root, int id) {
        View view = root.findViewById(id);
        if (view != null && isShowTapsSwitch(root.getResources(), id) && !LocalSettings.systemCopy()) {
            view.setVisibility(View.GONE);
        }
        return view;
    }

    private static boolean isShowTapsSwitch(Resources resources, int id) {
        if (showTapsSwitch != 0) return id == showTapsSwitch;
        try {
            if (!SHOW_TAPS_SWITCH.equals(resources.getResourceEntryName(id))) return false;
        } catch (Resources.NotFoundException e) {
            return false;
        }
        showTapsSwitch = id;
        return true;
    }
}
