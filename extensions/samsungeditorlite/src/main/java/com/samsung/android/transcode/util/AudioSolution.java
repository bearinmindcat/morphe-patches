package com.samsung.android.transcode.util;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One UI's audio helpers for the transcoder (native in One UI), in plain Java:
 * <ul>
 * <li>SRC, a sample-rate converter (linear interpolation, state kept between calls): the editor brings every audio
 * track and its background music to 48 kHz before encoding;</li>
 * <li>VSP, playback-speed change, used by Samsung's slow-motion videos only (which no other phone records): a plain
 * speed change of the samples;</li>
 * <li>NAAC, a software AAC encoder: not here, so it never starts and the editor uses Android's encoder.</li>
 * </ul>
 * PCM is 16-bit little-endian, interleaved; a 24-bit input arrives as sign-extended 32-bit little-endian words.
 * Input is read from the buffer's position, output is written from index 0; the calls return frames per channel.
 */
public class AudioSolution {
    private static final AtomicLong NEXT = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Resampler> STATES = new ConcurrentHashMap<>();

    private static final class Resampler {
        int channels = 2;
        int inBits = 16;
        /** Input samples per output sample. */
        double step = 1d;
        /** Where the next output sample lies, in input frames from the start of the next input block. */
        double position;
        short[] previous = new short[2];

        void configure(int outRate, int inRate, int channels, int inBits) {
            this.channels = Math.max(1, channels);
            this.inBits = inBits;
            this.step = outRate > 0 && inRate > 0 ? inRate / (double) outRate : 1d;
            if (previous.length != this.channels) previous = new short[this.channels];
            position = 0;
        }

        int run(ByteBuffer in, ByteBuffer out, int frames) {
            if (in == null || out == null || frames <= 0) return 0;
            int base = in.position();
            int frameBytes = channels * (inBits == 24 ? 4 : 2);
            frames = Math.min(frames, (in.limit() - base) / frameBytes);
            if (frames <= 0) return 0;
            int maxOut = out.capacity() / (channels * 2);
            int written = 0;
            double t = position;
            while (written < maxOut) {
                int i = (int) Math.floor(t);
                if (i + 1 > frames - 1) break;
                double f = t - i;
                for (int c = 0; c < channels; c++) {
                    int a = i < 0 ? previous[c] : sample(in, base, i, c, frameBytes);
                    int b = sample(in, base, i + 1, c, frameBytes);
                    int v = (int) Math.round(a + (b - a) * f);
                    int at = (written * channels + c) * 2;
                    out.put(at, (byte) v);
                    out.put(at + 1, (byte) (v >> 8));
                }
                written++;
                t += step;
            }
            position = t - frames;
            for (int c = 0; c < channels; c++) previous[c] = (short) sample(in, base, frames - 1, c, frameBytes);
            return written;
        }

        private int sample(ByteBuffer in, int base, int frame, int channel, int frameBytes) {
            int at = base + frame * frameBytes + channel * (inBits == 24 ? 4 : 2);
            if (inBits == 24) {
                int v = (in.get(at) & 0xff) | (in.get(at + 1) & 0xff) << 8 | (in.get(at + 2) & 0xff) << 16
                        | in.get(at + 3) << 24;
                return Math.max(-32768, Math.min(32767, v >> 8));
            }
            return (short) ((in.get(at) & 0xff) | in.get(at + 1) << 8);
        }
    }

    public AudioSolution() {
    }

    private static long create() {
        long handle = NEXT.getAndIncrement();
        STATES.put(handle, new Resampler());
        return handle;
    }

    public long SRCCreate() {
        return create();
    }

    public int SRCInit(long handle, int outRate, int inRate, int channels, int outBits, int inBits) {
        Resampler r = STATES.get(handle);
        if (r == null) return -1;
        r.configure(outRate, inRate, channels, inBits);
        return 0;
    }

    public int SRCExe2(long handle, ByteBuffer in, ByteBuffer out, int frames) {
        Resampler r = STATES.get(handle);
        return r != null ? r.run(in, out, frames) : 0;
    }

    public void SRCDestroy(long handle) {
        STATES.remove(handle);
    }

    public long VSPCreate() {
        return create();
    }

    public void VSPInit(long handle, int rate, int bits) {
        Resampler r = STATES.get(handle);
        if (r != null) r.configure(rate, rate, 2, bits);
    }

    /** The playback speed: 0.5 makes the audio twice as long. */
    public void VSPSetPar(long handle, float speed) {
        Resampler r = STATES.get(handle);
        if (r != null && speed > 0) r.step = speed;
    }

    public int VSPExe2(long handle, ByteBuffer in, ByteBuffer out, int frames) {
        return SRCExe2(handle, in, out, frames);
    }

    public int VSPExe_buffered2(long handle, ByteBuffer out, ByteBuffer in, int frames) {
        return SRCExe2(handle, in, out, frames);
    }

    public void VSPDestroy(long handle) {
        STATES.remove(handle);
    }

    public long NAACEncoderInit(int channels, int rate) {
        return 0L;
    }

    public int NAACEncoderExe(long handle, ByteBuffer in, ByteBuffer out, int frames) {
        return 0;
    }

    public boolean NAACEncoderDeInit(long handle) {
        return true;
    }
}
