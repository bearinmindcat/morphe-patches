package org.ungoogled.samsungsoundassistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.Map;

/**
 * Individual app volumes off One UI. Music and video apps announce each audio session they play to equalizers
 * (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION); for an app set below 100 % each of its sessions gets a
 * DynamicsProcessing input gain of 20·log10(volume/100) dB. Apps that announce no session keep their volume.
 * AppVolumeService keeps the announcements coming while any app is turned down.
 */
public final class AppVolumes {
    private static final String TAG = "UA";
    /** Session -> its effect, and session -> the package that announced it. */
    private static final SparseArray<DynamicsProcessing> EFFECTS = new SparseArray<>();
    private static final Map<Integer, String> OWNERS = new HashMap<>();

    private AppVolumes() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ua_sound_assistant", Context.MODE_PRIVATE);
    }

    public static int get(Context c, int uid) {
        return prefs(c).getInt("app_volume_" + uid, 100);
    }

    public static void set(Context c, int uid, int volume) {
        volume = Math.max(0, Math.min(100, volume));
        prefs(c).edit().putInt("app_volume_" + uid, volume).apply();
        synchronized (EFFECTS) {
            for (Map.Entry<Integer, String> e : OWNERS.entrySet()) {
                if (uid(c, e.getValue()) == uid) apply(c, e.getKey(), e.getValue());
            }
        }
        AppVolumeService.update(c);
    }

    /** Whether any app is turned down, so the service is needed. */
    static boolean anyDown(Context c) {
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getKey().startsWith("app_volume_") && e.getValue() instanceof Integer && (Integer) e.getValue() < 100) return true;
        }
        return false;
    }

    private static int uid(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    static float gainDb(int volume) {
        return volume <= 0 ? -96f : (float) (20 * Math.log10(volume / 100.0));
    }

    /** An app announced (open) or ended (close) an audio session. */
    static void onSession(Context c, Intent intent) {
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE);
        String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (session <= 0 || pkg == null) return;
        synchronized (EFFECTS) {
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                OWNERS.put(session, pkg);
                apply(c, session, pkg);
            } else {
                OWNERS.remove(session);
                DynamicsProcessing dp = EFFECTS.get(session);
                if (dp != null) dp.release();
                EFFECTS.remove(session);
            }
        }
    }

    private static void apply(Context c, int session, String pkg) {
        int volume = get(c, uid(c, pkg));
        DynamicsProcessing dp = EFFECTS.get(session);
        try {
            if (volume >= 100) {
                if (dp != null) dp.setEnabled(false);
                return;
            }
            if (dp == null) {
                dp = new DynamicsProcessing(0, session, null);
                EFFECTS.put(session, dp);
            }
            dp.setInputGainAllChannelsTo(gainDb(volume));
            dp.setEnabled(true);
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "app volume " + pkg + " session " + session + " -> " + volume + " %");
        } catch (RuntimeException e) {
            Log.w(TAG, "app volume " + pkg + " session " + session, e);
        }
    }
}
