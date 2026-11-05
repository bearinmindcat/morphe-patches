package org.ungoogled.samsungsoundassistant;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Sound Assistant's main screen off One UI. Most of its settings are carried out by One UI's own audio framework,
 * SystemUI and Samsung's Settings app (volume panel, volume keys and media keys, step volume, Samsung's sound
 * effects, vibration patterns, Bluetooth delay, headphone alerts). Elsewhere they did nothing, or read back as off a
 * moment after they were turned on, so they are hidden.
 *
 * Multi sound is the one that looked like it worked: it lets two apps keep playing by keeping audio focus from
 * moving. Audio focus is the system's (AudioService); its per-app exemption is One UI's, and Android's own
 * "all apps" switch (Settings.System multi_audio_focus_enabled) may only be written by the system or adb, and is
 * read when the phone starts. The app's write was refused, so the switch flipped back off. The row stays, unavailable,
 * and shows "All apps" when that switch was turned on with adb.
 */
public final class Unavailable {
    private static final String TAG = "UA";

    /** The main screen's rows that need One UI (keys from res/xml/preference_settings_fragment.xml). */
    private static final String[] NEEDS_ONE_UI = {
            "pref_key_volumestar", "pref_key_volumetheme",
            "pref_key_media_volume", "pref_key_mute_media_by_vibrate_or_silent_mode", "pref_key_step_volume",
            "pref_key_volume_key_long_press", "pref_key_favorite_media",
            "pref_key_av_sync", "pref_key_advanced_setting", "pref_key_ignore_ducking",
            "pref_key_record_voice", "pref_key_create_vibration", "pref_key_concert_hall", "pref_key_voice_changer",
            "pref_key_specify_recording",
    };
    static final String MULTI_SOUND = "pref_key_ignore_audio_focus";
    static final String MULTI_SOUND_REASON = "Unavailable: Android lets one app play at a time";

    private Unavailable() {
    }

    static boolean isOneUi(Context c) {
        return c.getPackageManager().hasSystemFeature("com.samsung.feature.samsung_experience_mobile");
    }

    /** Android's own "all apps" multi audio focus switch, which only adb or the system can turn on. */
    static boolean multiAudioFocus(Context c) {
        return Settings.System.getInt(c.getContentResolver(), "multi_audio_focus_enabled", 0) != 0;
    }

    /**
     * Start of the main screen's onResume (a PreferenceFragmentCompat; reached by reflection, its classes are the
     * app's). onResume sets every row's state, so the rows are changed once it has finished.
     */
    public static void mainScreen(final Object fragment) {
        new Handler(Looper.getMainLooper()).post(() -> apply(fragment));
    }

    private static void apply(Object fragment) {
        try {
            Context c = (Context) fragment.getClass().getMethod("getContext").invoke(fragment);
            if (c == null || isOneUi(c)) return;
            Method find = fragment.getClass().getMethod("findPreference", CharSequence.class);
            for (String key : NEEDS_ONE_UI) {
                Object p = find.invoke(fragment, key);
                if (p != null) invoke(p, "setVisible", boolean.class, false);
            }
            Object multi = find.invoke(fragment, MULTI_SOUND);
            if (multi != null) {
                boolean on = multiAudioFocus(c);
                invoke(multi, "setChecked", boolean.class, on);
                invoke(multi, "setEnabled", boolean.class, false);
                CharSequence allApps = c.getText(c.getResources().getIdentifier("header_all_apps", "string", c.getPackageName()));
                invoke(multi, "setSummary", CharSequence.class, on ? allApps : MULTI_SOUND_REASON);
            }
            hideEmptyGroups(fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "sound assistant: could not hide One UI settings", e);
        }
    }

    /** Hides a category whose rows are all hidden (an empty one is a spacer and stays). */
    private static void hideEmptyGroups(Object group) throws ReflectiveOperationException {
        if (group == null) return;
        Method count = group.getClass().getMethod("getPreferenceCount");
        Method child = group.getClass().getMethod("getPreference", int.class);
        int n = (Integer) count.invoke(group);
        for (int i = 0; i < n; i++) {
            Object p = child.invoke(group, i);
            Method subCount;
            try {
                subCount = p.getClass().getMethod("getPreferenceCount");
            } catch (NoSuchMethodException notAGroup) {
                continue;
            }
            int rows = (Integer) subCount.invoke(p);
            if (rows == 0) continue;
            Method subChild = p.getClass().getMethod("getPreference", int.class);
            boolean anyVisible = false;
            for (int j = 0; j < rows && !anyVisible; j++) {
                Object row = subChild.invoke(p, j);
                anyVisible = (Boolean) row.getClass().getMethod("isVisible").invoke(row);
            }
            if (!anyVisible) invoke(p, "setVisible", boolean.class, false);
        }
    }

    private static void invoke(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        target.getClass().getMethod(name, type).invoke(target, value);
    }

    /**
     * The links under the main screen ("Separate app sound", "Sound quality and effects") open One UI's screens; a
     * link nothing on the phone can open is left out instead of doing nothing when tapped.
     */
    public static boolean canOpen(Context c, Intent intent) {
        try {
            // PackageManager, not Intent.resolveActivity: that one returns an explicit intent's component unchecked.
            return c == null || isOneUi(c) || c.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
