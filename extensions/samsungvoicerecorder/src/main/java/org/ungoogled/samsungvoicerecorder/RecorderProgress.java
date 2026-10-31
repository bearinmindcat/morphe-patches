package org.ungoogled.samsungvoicerecorder;

import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * One UI's MediaRecorder reports the recorded time every few milliseconds (MediaRecorder.semSetDurationInterval, info
 * 901 with the milliseconds recorded): Voice Recorder's timer, wave and the saved length all come from it, so off One
 * UI a recording ran at 00:00.0. Here the same reports come from a clock that runs while the recorder records.
 */
public final class RecorderProgress {
    /** One UI's "recording in progress" info code. */
    private static final int INFO_PROGRESS = 901;

    private static final class State {
        MediaRecorder.OnInfoListener listener;
        int interval;
        long recorded;
        long since = -1;
        Handler handler;
        Runnable tick;
    }

    private static final Map<MediaRecorder, State> STATES = new WeakHashMap<>();

    private RecorderProgress() {
    }

    private static synchronized State state(MediaRecorder recorder) {
        State s = STATES.get(recorder);
        if (s == null) {
            s = new State();
            STATES.put(recorder, s);
        }
        return s;
    }

    /** MediaRecorder.semSetDurationInterval(int), which this phone lacks (see Fallbacks). */
    static void interval(MediaRecorder recorder, int milliseconds) {
        state(recorder).interval = Math.max(milliseconds, 10);
    }

    private static long elapsed(State s) {
        return s.recorded + (s.since < 0 ? 0 : SystemClock.elapsedRealtime() - s.since);
    }

    private static void run(MediaRecorder recorder, State s) {
        if (s.interval <= 0 || s.listener == null) return;
        s.since = SystemClock.elapsedRealtime();
        if (s.handler == null) {
            Looper looper = Looper.myLooper();
            s.handler = new Handler(looper != null ? looper : Looper.getMainLooper());
        }
        if (s.tick == null) {
            s.tick = new Runnable() {
                @Override
                public void run() {
                    if (s.since < 0) return;
                    s.listener.onInfo(recorder, INFO_PROGRESS, (int) elapsed(s));
                    if (s.since >= 0) s.handler.postDelayed(this, s.interval);
                }
            };
        }
        s.handler.removeCallbacks(s.tick);
        s.handler.postDelayed(s.tick, s.interval);
    }

    private static void halt(State s) {
        if (s.since >= 0) s.recorded += SystemClock.elapsedRealtime() - s.since;
        s.since = -1;
        if (s.handler != null && s.tick != null) s.handler.removeCallbacks(s.tick);
    }

    public static void setOnInfoListener(MediaRecorder recorder, MediaRecorder.OnInfoListener listener) {
        recorder.setOnInfoListener(listener);
        state(recorder).listener = listener;
    }

    public static void start(MediaRecorder recorder) {
        recorder.start();
        State s = state(recorder);
        s.recorded = 0;
        run(recorder, s);
    }

    public static void pause(MediaRecorder recorder) {
        recorder.pause();
        halt(state(recorder));
    }

    public static void resume(MediaRecorder recorder) {
        recorder.resume();
        run(recorder, state(recorder));
    }

    public static void stop(MediaRecorder recorder) {
        halt(state(recorder));
        recorder.stop();
    }

    public static void reset(MediaRecorder recorder) {
        halt(state(recorder));
        recorder.reset();
        synchronized (RecorderProgress.class) {
            STATES.remove(recorder);
        }
    }

    public static void release(MediaRecorder recorder) {
        halt(state(recorder));
        recorder.release();
        synchronized (RecorderProgress.class) {
            STATES.remove(recorder);
        }
    }
}
