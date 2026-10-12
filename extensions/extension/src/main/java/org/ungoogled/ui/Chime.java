package org.ungoogled.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The short sounds of the speed alerts, made on the phone: no sound files are added to Maps. They
 * play as navigation guidance does -- through the car on Android Auto and Bluetooth, music turned
 * down for them -- one at a time, on a thread of their own.
 */
final class Chime {
    private Chime() {}

    private static final int RATE = 44100;
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ua-chime");
        t.setDaemon(true);
        return t;
    });

    /** Over the speed limit: two rising notes, a soft "ding-ding". */
    static void speeding() {
        play(new float[]{988f, 1319f}, 150, 30);
    }

    /** A camera ahead: three quick high notes, unlike the speeding chime. */
    static void camera() {
        play(new float[]{1568f, 1568f, 2093f}, 110, 50);
    }

    /** A Waze alert ahead: down a note and back up, unlike the other two. */
    static void alert() {
        play(new float[]{1319f, 988f, 1319f}, 130, 40);
    }

    /** [notes] in Hz, each [noteMs] long with [gapMs] of silence after it. */
    private static void play(float[] notes, int noteMs, int gapMs) {
        AUDIO.execute(() -> {
            Context c = Shapes.appContext();
            if (c == null) return;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            AudioTrack track = null;
            try {
                if (am != null) am.requestAudioFocus(focus);
                short[] pcm = synth(notes, noteMs, gapMs);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(pcm.length * 1000L / RATE + 60);
            } catch (Throwable t) {
                android.util.Log.w("UA", "chime", t);
            } finally {
                if (track != null) track.release();
                if (am != null) am.abandonAudioFocusRequest(focus);
            }
        });
    }

    /** Bell-like notes: a sine with a little of its octave, a 6 ms rise and a fast decay. */
    private static short[] synth(float[] notes, int noteMs, int gapMs) {
        int note = RATE * noteMs / 1000, gap = RATE * gapMs / 1000, rise = RATE * 6 / 1000;
        short[] out = new short[notes.length * (note + gap)];
        for (int n = 0; n < notes.length; n++) {
            int base = n * (note + gap);
            double w = 2 * Math.PI * notes[n] / RATE;
            for (int i = 0; i < note; i++) {
                double env = (i < rise ? i / (double) rise : 1.0) * Math.exp(-4.0 * i / note);
                double s = Math.sin(w * i) + 0.25 * Math.sin(2 * w * i);
                out[base + i] = (short) (s / 1.25 * env * 0.7 * Short.MAX_VALUE);
            }
        }
        return out;
    }
}
