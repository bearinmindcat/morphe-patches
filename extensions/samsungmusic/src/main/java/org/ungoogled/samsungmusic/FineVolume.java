package org.ungoogled.samsungmusic;

import android.media.AudioManager;

/**
 * One UI's fine volume: 0..150 for a stream (10 fine steps per regular step on a Samsung). Off One UI this reads and
 * writes the stream's regular index scaled to that range, so the app's sliders move the real volume.
 */
final class FineVolume {
    static final int MAX = 150;

    private FineVolume() {
    }

    static int get(AudioManager am, int stream) {
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        return Math.round(am.getStreamVolume(stream) * (float) MAX / max);
    }

    static void set(AudioManager am, int stream, int fine, int flags) {
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        int index = Math.max(0, Math.min(max, Math.round(fine * (float) max / MAX)));
        am.setStreamVolume(stream, index, flags & AudioManager.FLAG_SHOW_UI);
    }
}
