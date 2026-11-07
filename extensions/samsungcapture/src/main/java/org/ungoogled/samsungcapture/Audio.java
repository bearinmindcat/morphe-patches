package org.ungoogled.samsungcapture;

import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;

/**
 * The recording's sound. One UI's recorder records "Media sounds" from a Samsung audio source for app playback
 * (MediaRecorder.semGetInputSource(12), which needs CAPTURE_AUDIO_OUTPUT: the system only). Here that source is
 * Android's app audio capture (AudioPlaybackCapture: media, games and other apps' sounds that allow it) through the
 * recording's capture consent; "Media sounds and mic" records both as Samsung does, with two recorders.
 */
public final class Audio {
    /** What SepWrapper.MediaRecorder.semGetInputSource(12) answers now: no real audio source has this number. */
    static final int PLAYBACK = 0x5ca9;

    private Audio() {
    }

    /** SepWrapper.MediaRecorder.semGetInputSource: Samsung's source for app playback becomes our marker. */
    public static int inputSource(int source) {
        return source == 12 ? PLAYBACK : MediaRecorder.AudioSource.DEFAULT;
    }

    /**
     * AudioUtil.isSystemAudioSupported: One UI asks for its own in-app sound permission and a test recording from its
     * playback source. Here app audio is Android's playback capture, which needs only the microphone permission.
     */
    public static boolean isSystemAudioSupported(android.content.Context context) {
        return context.checkSelfPermission("android.permission.RECORD_AUDIO") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** AudioRecord.Builder.setAudioSource: app playback is captured through the consent, the rest as asked. */
    public static AudioRecord.Builder setAudioSource(AudioRecord.Builder builder, int source) {
        if (source != PLAYBACK) return builder.setAudioSource(source);
        MediaProjection projection = Projection.current();
        if (projection == null) {
            Log.w(Projection.TAG, "no capture consent for app audio; recording the microphone");
            return builder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        return builder.setAudioPlaybackCaptureConfig(config);
    }
}
