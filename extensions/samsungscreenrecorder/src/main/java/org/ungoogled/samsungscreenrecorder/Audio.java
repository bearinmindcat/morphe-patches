package org.ungoogled.samsungscreenrecorder;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

/**
 * The recording's sound. One UI's preinstalled recorder records "System sounds" from the remote submix, which needs
 * CAPTURE_AUDIO_OUTPUT (the system only), and "System sounds and mic" from the microphone alone. Anywhere else the
 * system sounds come from Android's app audio capture (AudioPlaybackCapture: media, games and other apps' sounds that
 * allow it) through the user's capture consent, and "and mic" mixes the microphone in.
 */
public final class Audio {
    private Audio() {
    }

    /** The recorder's new AudioRecord(source, rate, channels, encoding, buffer size). */
    public static AudioRecord create(int source, int rate, int channels, int encoding, int size) {
        MediaProjection projection = Build.VERSION.SDK_INT >= 29 ? Projection.get() : null;
        if (projection == null || (source != MediaRecorder.AudioSource.REMOTE_SUBMIX && source != MediaRecorder.AudioSource.MIC)) {
            return new AudioRecord(source, rate, channels, encoding, size);
        }
        AudioRecord apps = apps(projection, rate, channels, encoding, size);
        if (source == MediaRecorder.AudioSource.MIC) return new MixedRecord(rate, channels, encoding, size, apps);
        return apps != null ? apps : new AudioRecord(source, rate, channels, encoding, size);
    }

    /** App audio through the capture consent, or null where Android refuses it. */
    private static AudioRecord apps(MediaProjection projection, int rate, int channels, int encoding, int size) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(channels).build())
                    .setBufferSizeInBytes(Math.max(size, 4096) * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() == AudioRecord.STATE_INITIALIZED) return record;
            record.release();
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "app audio refused", e);
        }
        return null;
    }

    /** The microphone with app audio mixed in; the microphone keeps the time. */
    static final class MixedRecord extends AudioRecord {
        private final AudioRecord apps;
        private final boolean pcm16;
        private byte[] pending = new byte[0];
        private int pendingLength;
        private byte[] scratch = new byte[0];

        MixedRecord(int rate, int channels, int encoding, int size, AudioRecord apps) {
            super(MediaRecorder.AudioSource.MIC, rate, channels, encoding, size);
            this.apps = apps;
            this.pcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_DEFAULT;
        }

        @Override
        public void startRecording() throws IllegalStateException {
            super.startRecording();
            if (apps == null) return;
            try {
                apps.startRecording();
            } catch (IllegalStateException e) {
                Log.w(Projection.TAG, "app audio not started", e);
            }
        }

        @Override
        public int read(byte[] data, int offset, int size) {
            int read = super.read(data, offset, size);
            if (read <= 0 || apps == null || !pcm16 || apps.getRecordingState() != RECORDSTATE_RECORDING) return read;
            if (pending.length < read * 4) {
                byte[] grown = new byte[read * 4];
                System.arraycopy(pending, 0, grown, 0, pendingLength);
                pending = grown;
            }
            if (scratch.length < read * 2) scratch = new byte[read * 2];
            int room = Math.min(pending.length - pendingLength, read * 2);
            int got = room > 0 ? apps.read(scratch, 0, room & ~1, AudioRecord.READ_NON_BLOCKING) : 0;
            if (got > 0) {
                System.arraycopy(scratch, 0, pending, pendingLength, got);
                pendingLength += got;
            }
            int mix = Math.min(read, pendingLength) & ~1;
            for (int i = 0; i < mix; i += 2) {
                int a = (short) ((data[offset + i] & 0xff) | (data[offset + i + 1] << 8));
                int b = (short) ((pending[i] & 0xff) | (pending[i + 1] << 8));
                int sum = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, a + b));
                data[offset + i] = (byte) sum;
                data[offset + i + 1] = (byte) (sum >> 8);
            }
            // Keep app audio no more than a read behind the microphone.
            int drop = pendingLength - mix > read ? pendingLength - read : mix;
            System.arraycopy(pending, drop, pending, 0, pendingLength - drop);
            pendingLength -= drop;
            return read;
        }

        @Override
        public void stop() throws IllegalStateException {
            if (apps != null) {
                try {
                    apps.stop();
                } catch (IllegalStateException e) {
                    // Never started.
                }
            }
            super.stop();
        }

        @Override
        public void release() {
            if (apps != null) apps.release();
            super.release();
        }
    }
}
