package com.samsung.android.media;

import android.app.PendingIntent;
import android.content.Context;

import org.ungoogled.samsungsoundassistant.AppVolumes;

/**
 * One UI's Sound Assistant service, off One UI (no Samsung code; on a Samsung phone the framework's own class loads
 * first and this never runs). Individual app volumes are real: they attenuate the audio sessions apps announce to
 * equalizers (see AppVolumes). The rest only keeps what the app set, so its screens show it; Android has no public
 * way to change the volume step, route one app to another output, or let two apps hold audio focus.
 */
public class SemSoundAssistantManager {
    private final Context context;

    public SemSoundAssistantManager(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    public int getApplicationVolume(int uid) {
        return AppVolumes.get(context, uid);
    }

    public void setApplicationVolume(int uid, int volume) {
        AppVolumes.set(context, uid, volume);
    }

    public int getMediaVolumeInterval() {
        return AppVolumes.prefs(context).getInt("media_volume_interval", 10);
    }

    public void setMediaVolumeInterval(int interval) {
        AppVolumes.prefs(context).edit().putInt("media_volume_interval", interval).apply();
    }

    public java.lang.String getSoundAssistantProperty(java.lang.String key) {
        return AppVolumes.prefs(context).getString("property_" + key, "");
    }

    public void setSoundAssistantProperty(java.lang.String property) {
        int eq = property.indexOf('=');
        if (eq > 0) AppVolumes.prefs(context).edit().putString("property_" + property.substring(0, eq), property.substring(eq + 1)).apply();
    }

    public boolean getVolumeMode(int mode) {
        return AppVolumes.prefs(context).getBoolean("volume_mode_" + mode, false);
    }

    public void setVolumeMode(int mode, boolean on) {
        AppVolumes.prefs(context).edit().putBoolean("volume_mode_" + mode, on).apply();
    }

    public int getUidIgnoredAudioFocus() {
        return AppVolumes.prefs(context).getInt("ignored_focus_uid", -1);
    }

    public void ignoreAudioFocusForApp(int uid, boolean ignore) {
        AppVolumes.prefs(context).edit().putInt("ignored_focus_uid", ignore ? uid : -1).apply();
    }

    public boolean isMultiSoundOn() {
        return AppVolumes.prefs(context).getBoolean("multi_sound", false);
    }

    public void setMultiSoundOn(boolean on) {
        AppVolumes.prefs(context).edit().putBoolean("multi_sound", on).apply();
    }

    public int getDeviceForStream(int stream) {
        return 0;
    }

    public void setDeviceForStream(int stream, int device) {
    }

    public boolean setDefaultSoundOutputDevice(int device) {
        return false;
    }

    public void setMultiSoundTargetDevice(int uid, int device) {
    }

    public void setSoundSettingEventBroadcastIntent(int event, PendingIntent intent) {
    }
}
