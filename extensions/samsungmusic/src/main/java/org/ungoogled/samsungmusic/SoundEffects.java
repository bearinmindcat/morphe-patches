package org.ungoogled.samsungmusic;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.audiofx.AudioEffect;

/**
 * "Sound quality and effects" opens One UI's SoundAlive, an app only One UI has; Music then falls back to Android's
 * standard audio effect panel, but only when SoundAlive is installed. Off One UI the button did nothing; this lets it
 * open the phone's own equalizer (whatever answers the standard intent).
 */
public final class SoundEffects {
    private SoundEffects() {
    }

    public static boolean available(boolean soundAlive, Context context) {
        if (soundAlive || context == null) return soundAlive;
        Intent panel = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        return !context.getPackageManager()
                .queryIntentActivities(panel, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }
}
