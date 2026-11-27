package org.ungoogled.waze;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The short sounds of the alerts, made on the phone: no sound files are added to Waze. They
 * play as navigation guidance does -- through the car on Android Auto and Bluetooth, music turned
 * down for them -- one at a time, on a thread of their own.
 *
 * Speed camera alerts' Chime value (issue #87, Flock cameras use it too): Normal is the bell; Loud is
 * the same notes held at full scale, longer, twice; Alarm is Loud on the alarm stream, which has its
 * own volume and also plays from the phone's speaker.
 */
final class Chime {
    private Chime() {}

    private static final int RATE = 44100;
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ua-chime");
        t.setDaemon(true);
        return t;
    });

    /** The Chime value's choices. */
    static final String NORMAL = "normal", LOUD = "loud", ALARM = "alarm";

    private static final float[] CAMERA = {1568f, 1568f, 2093f};

    /** A camera ahead: three quick high notes, unlike the speeding chime; as loud as the Chime value says. */
    static void camera() {
        String how = Customization.string(Customization.CAMERA_CHIME, NORMAL);
        if (LOUD.equals(how) || ALARM.equals(how)) {
            float[] twice = new float[CAMERA.length * 2];
            System.arraycopy(CAMERA, 0, twice, 0, CAMERA.length);
            System.arraycopy(CAMERA, 0, twice, CAMERA.length, CAMERA.length);
            play(twice, 170, 60, true, ALARM.equals(how) ? AudioAttributes.USAGE_ALARM
                    : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        } else {
            play(CAMERA, 110, 50, false, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        }
    }

    /** [notes] in Hz, each [noteMs] long with [gapMs] of silence after it, [loud] or bell-like, for [usage]. */
    private static void play(float[] notes, int noteMs, int gapMs, boolean loud, int usage) {
        AUDIO.execute(() -> {
            Context c = Customization.appContext();
            if (c == null) return;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            AudioTrack track = null;
            try {
                if (am != null) am.requestAudioFocus(focus);
                short[] pcm = loud ? synthLoud(notes, noteMs, gapMs) : synth(notes, noteMs, gapMs);
                if (android.util.Log.isLoggable("UA", android.util.Log.DEBUG)) {
                    android.util.Log.d("UA", "chime: " + notes.length + " notes, " + (loud ? "loud" : "bell") + ", usage " + usage);
                }
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

    /**
     * The same notes made as loud as a sound can be: held at full scale instead of dying away, rounded
     * off towards a square wave (about 16 dB above the bell), with short ramps so they do not click.
     */
    private static short[] synthLoud(float[] notes, int noteMs, int gapMs) {
        int note = RATE * noteMs / 1000, gap = RATE * gapMs / 1000, ramp = RATE * 8 / 1000;
        double drive = 3.0, norm = Math.tanh(drive * 1.1);
        short[] out = new short[notes.length * (note + gap)];
        for (int n = 0; n < notes.length; n++) {
            int base = n * (note + gap);
            double w = 2 * Math.PI * notes[n] / RATE;
            for (int i = 0; i < note; i++) {
                double env = Math.min(1.0, Math.min(i, note - 1 - i) / (double) ramp);
                double s = Math.tanh(drive * (Math.sin(w * i) + 0.1 * Math.sin(3 * w * i))) / norm;
                out[base + i] = (short) Math.max(-Short.MAX_VALUE, Math.min(Short.MAX_VALUE, s * env * 0.97 * Short.MAX_VALUE));
            }
        }
        return out;
    }
}
