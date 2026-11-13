package com.samsung.android.media;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One UI's asynchronous frame grabber (the timeline thumbnails), rebuilt on Android's MediaMetadataRetriever (no
 * Samsung code): init() opens the video on a worker thread and reports OnInitCompleteListener; start() decodes each
 * requested time (ms) and hands it to OnVideoFrameListener with that time, then reports OnDecodingCompleteListener
 * with the number of frames. On a Samsung phone the framework's own class loads first, so this one never runs there.
 */
public class SemAsyncVideoFrameDecoder {
    private static final String TAG = "UA";
    /** Samsung's seek options: 2 = nearest sync frame (fast), 3 = the exact frame. */
    private static final int SEEK_EXACT = 3;

    private final HandlerThread thread = new HandlerThread("SemAsyncVideoFrameDecoder");
    private final Handler worker;

    private OnInitCompleteListener initListener;
    private OnVideoFrameListener frameListener;
    private OnDecodingCompleteListener completeListener;

    private MediaMetadataRetriever retriever;
    private ParcelFileDescriptor descriptor;
    private final List<Integer> times = new ArrayList<>();
    private int width;
    private int height;
    private int seekOption = 2;
    private volatile boolean stopped;
    private volatile boolean released;

    public SemAsyncVideoFrameDecoder() {
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public void init(String path) throws IOException {
        open(r -> r.setDataSource(path));
    }

    public void init(FileDescriptor fd) throws IOException {
        init(fd, 0L, 0L);
    }

    public void init(FileDescriptor fd, long offset, long length) throws IOException {
        // The caller may close its descriptor as soon as init returns.
        ParcelFileDescriptor copy = ParcelFileDescriptor.dup(fd);
        descriptor = copy;
        open(r -> {
            if (length > 0) r.setDataSource(copy.getFileDescriptor(), offset, length);
            else r.setDataSource(copy.getFileDescriptor());
        });
    }

    private interface Source {
        void set(MediaMetadataRetriever retriever) throws IOException;
    }

    private void open(Source source) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            source.set(r);
        } catch (IOException | RuntimeException e) {
            r.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        retriever = r;
        worker.post(() -> {
            if (!released && initListener != null) initListener.onInitCompleted(this);
        });
    }

    public void setTargetFrameTimeList(List list) {
        synchronized (times) {
            times.clear();
            if (list == null) return;
            for (Object o : list) if (o instanceof Number) times.add(((Number) o).intValue());
        }
    }

    public void setOutputImageSize(int width, int height, boolean keepRatio) {
        this.width = width;
        this.height = height;
    }

    public void setOutputColorFormat(Bitmap.Config config) {
    }

    public void setPreferredCodec(int codec) {
    }

    public void setSeekOption(int option) {
        seekOption = option;
    }

    public void start() {
        stopped = false;
        worker.post(this::decode);
    }

    private void decode() {
        List<Integer> todo;
        synchronized (times) {
            todo = new ArrayList<>(times);
        }
        int count = 0;
        int option = seekOption == SEEK_EXACT ? MediaMetadataRetriever.OPTION_CLOSEST
                : MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
        for (int time : todo) {
            if (stopped || released || retriever == null) break;
            Bitmap frame = null;
            try {
                long at = time * 1000L;
                frame = width > 0 && height > 0
                        ? retriever.getScaledFrameAtTime(at, option, width, height)
                        : retriever.getFrameAtTime(at, option);
                if (frame == null && option != MediaMetadataRetriever.OPTION_CLOSEST_SYNC) {
                    frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "frame at " + time + " ms: " + e);
            }
            if (frame == null) continue;
            count++;
            if (frameListener != null) frameListener.onVideoFrame(this, frame, time, count - 1);
        }
        if (!released && completeListener != null) completeListener.onDecodingCompleted(this, count);
    }

    public void stop() {
        stopped = true;
    }

    public void reset() {
        stopped = true;
        synchronized (times) {
            times.clear();
        }
    }

    public void release() {
        stopped = true;
        released = true;
        worker.post(() -> {
            try {
                if (retriever != null) retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Already gone.
            }
            retriever = null;
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
                descriptor = null;
            }
            thread.quitSafely();
        });
    }

    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        initListener = listener;
    }

    public void setOnVideoFrameListener(OnVideoFrameListener listener) {
        frameListener = listener;
    }

    public void setOnDecodingCompleteListener(OnDecodingCompleteListener listener) {
        completeListener = listener;
    }

    public interface OnDecodingCompleteListener {
        void onDecodingCompleted(SemAsyncVideoFrameDecoder decoder, int frames);
    }

    public interface OnInitCompleteListener {
        void onInitCompleted(SemAsyncVideoFrameDecoder decoder);
    }

    public interface OnVideoFrameListener {
        void onVideoFrame(SemAsyncVideoFrameDecoder decoder, Bitmap frame, int timeMs, int index);
    }
}
