package com.samsung.android.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.TimedText;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One UI's media player, which Video Player plays every video with, rebuilt on Android's own MediaPlayer (no Samsung
 * code): init() prepares in the background and reports the tracks to OnInitCompleteListener, the rest is MediaPlayer
 * with Samsung's names. One UI's own extras (playback effects, temporal zoom, crop, super slow regions, background
 * music, its many setParameter keys) answer "not supported". Calls made before the player is ready are held until it
 * is, instead of putting MediaPlayer into its error state. On a Samsung phone the framework's own class loads first,
 * so this one never runs there.
 */
public class SemMediaPlayer {
    private static final String TAG = "UA";
    /** Samsung's track types beyond MediaPlayer's: a text or subtitle track added from a file. */
    private static final int TRACK_OUTBAND_TIMEDTEXT = 5;
    private static final int TRACK_OUTBAND_SUBTITLE = 6;
    /** setParameter key Video Player sets before an init it expects to finish before it returns. */
    private static final int KEY_SYNC_INIT = 2501;

    private final MediaPlayer player = new MediaPlayer();
    private final Handler handler;

    private OnInitCompleteListener initListener;
    private OnPlaybackCompleteListener completeListener;
    private OnErrorListener errorListener;
    private OnInfoListener infoListener;
    private OnVideoSizeChangedListener sizeListener;
    private OnSeekCompleteListener seekListener;
    private OnBufferingUpdateListener bufferingListener;

    private boolean syncInit;
    private boolean prepared;
    private boolean released;
    private boolean startWhenPrepared;
    private int seekWhenPrepared = -1;
    private int seekModeWhenPrepared;
    private PlaybackParams paramsWhenPrepared;

    /** Where the video came from, for frame grabs: a Context + Uri, or our own copy of a descriptor. */
    private Context sourceContext;
    private Uri sourceUri;
    private ParcelFileDescriptor sourceFd;
    private int frameRate;
    /** Where the video is drawn, for frame grabs. */
    private SurfaceHolder holder;
    private Surface surface;
    /** How many tracks the video itself has; anything after them was added from a file. */
    private int inbandTracks = -1;
    private final List<Integer> outbandSubtitleTracks = new ArrayList<>();

    public SemMediaPlayer() {
        Looper looper = Looper.myLooper();
        handler = new Handler(looper != null ? looper : Looper.getMainLooper());
        player.setOnPreparedListener(mp -> onPrepared());
        player.setOnCompletionListener(mp -> {
            if (completeListener != null) completeListener.onPlaybackComplete(this);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "SemMediaPlayer error " + what + "/" + extra);
            prepared = false;
            return errorListener != null && errorListener.onError(this, what, extra);
        });
        player.setOnInfoListener((mp, what, extra) -> infoListener != null && infoListener.onInfo(this, what, extra));
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (sizeListener != null) sizeListener.onVideoSizeChanged(this, width, height);
        });
        player.setOnSeekCompleteListener(mp -> {
            if (seekListener != null) seekListener.onSeekComplete(this);
        });
        player.setOnBufferingUpdateListener((mp, percent) -> {
            if (bufferingListener != null) bufferingListener.onBufferingUpdate(this, percent);
        });
    }

    // ---- set-up --------------------------------------------------------------------------------------------------

    public void init(Context context, Uri uri) {
        sourceContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        sourceUri = uri;
        try {
            player.setDataSource(context, uri);
        } catch (IOException | RuntimeException e) {
            failLater(e);
            return;
        }
        readFrameRate();
        prepare();
    }

    public void init(FileDescriptor fd) {
        try {
            // The caller closes its descriptor as soon as init returns.
            sourceFd = ParcelFileDescriptor.dup(fd);
            player.setDataSource(sourceFd.getFileDescriptor());
        } catch (IOException | RuntimeException e) {
            failLater(e);
            return;
        }
        readFrameRate();
        prepare();
    }

    private void prepare() {
        if (syncInit) {
            try {
                player.prepare();
            } catch (IOException | RuntimeException e) {
                failLater(e);
                return;
            }
            handler.post(this::onPrepared);
        } else {
            player.prepareAsync();
        }
    }

    private void failLater(Exception e) {
        Log.w(TAG, "SemMediaPlayer init failed", e);
        handler.post(() -> {
            if (!released && errorListener != null) {
                errorListener.onError(this, MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO);
            }
        });
    }

    private void onPrepared() {
        if (released || prepared) return;
        prepared = true;
        if (paramsWhenPrepared != null) {
            try {
                player.setPlaybackParams(paramsWhenPrepared);
            } catch (RuntimeException ignored) {
                // Unsupported speed: keep the normal one.
            }
            paramsWhenPrepared = null;
        }
        if (seekWhenPrepared >= 0) {
            player.seekTo(seekWhenPrepared, seekModeWhenPrepared);
            seekWhenPrepared = -1;
        }
        if (initListener != null) initListener.onInitComplete(this, getTrackInfo());
        if (startWhenPrepared) {
            startWhenPrepared = false;
            player.start();
        }
    }

    private void readFrameRate() {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (sourceFd != null) extractor.setDataSource(sourceFd.getFileDescriptor());
            else extractor.setDataSource(sourceContext, sourceUri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    } catch (ClassCastException e) {
                        frameRate = Math.round(format.getFloat(MediaFormat.KEY_FRAME_RATE));
                    }
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Streams and unknown containers: no frame rate.
        } finally {
            extractor.release();
        }
    }

    public void reset() {
        prepared = false;
        startWhenPrepared = false;
        seekWhenPrepared = -1;
        paramsWhenPrepared = null;
        inbandTracks = -1;
        outbandSubtitleTracks.clear();
        frameRate = 0;
        closeSource();
        player.reset();
    }

    public void release() {
        released = true;
        prepared = false;
        closeSource();
        player.release();
    }

    private void closeSource() {
        sourceContext = null;
        sourceUri = null;
        if (sourceFd != null) {
            try {
                sourceFd.close();
            } catch (IOException ignored) {
                // Already gone.
            }
            sourceFd = null;
        }
    }

    // ---- playback ------------------------------------------------------------------------------------------------

    public void start() {
        if (!prepared) {
            startWhenPrepared = true;
            return;
        }
        player.start();
    }

    public void pause() {
        if (!prepared) {
            startWhenPrepared = false;
            return;
        }
        player.pause();
    }

    public boolean isPlaying() {
        return !released && player.isPlaying();
    }

    public void seekTo(int msec) {
        seekTo(msec, MediaPlayer.SEEK_PREVIOUS_SYNC);
    }

    /** Modes 0-3 are MediaPlayer's; Samsung's own (4, where the seek bar lands) go to the exact frame. */
    public void seekTo(int msec, int mode) {
        int androidMode = mode >= MediaPlayer.SEEK_PREVIOUS_SYNC && mode <= MediaPlayer.SEEK_CLOSEST ? mode
                : MediaPlayer.SEEK_CLOSEST;
        if (!prepared) {
            seekWhenPrepared = msec;
            seekModeWhenPrepared = androidMode;
            return;
        }
        player.seekTo(msec, androidMode);
    }

    public int getCurrentPosition() {
        if (released) return 0;
        if (!prepared) return Math.max(seekWhenPrepared, 0);
        return player.getCurrentPosition();
    }

    public int getLastRenderedVideoPosition() {
        return getCurrentPosition();
    }

    public int getDuration() {
        return prepared ? player.getDuration() : -1;
    }

    public void setLooping(boolean looping) {
        player.setLooping(looping);
    }

    public void setVolume(float left, float right) {
        player.setVolume(left, right);
    }

    public void setAudioAttributes(AudioAttributes attributes) {
        player.setAudioAttributes(attributes);
    }

    public PlaybackParams getPlaybackParams() {
        if (!prepared) return paramsWhenPrepared != null ? paramsWhenPrepared : new PlaybackParams().setSpeed(1f);
        return player.getPlaybackParams();
    }

    public void setPlaybackParams(PlaybackParams params) {
        if (!prepared) {
            paramsWhenPrepared = params;
            return;
        }
        player.setPlaybackParams(params);
    }

    public void setScreenOnWhilePlaying(boolean on) {
        player.setScreenOnWhilePlaying(on);
    }

    public void setDisplay(SurfaceHolder holder) {
        this.holder = holder;
        surface = null;
        player.setDisplay(holder);
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        holder = null;
        player.setSurface(surface);
    }

    // ---- tracks and subtitles ------------------------------------------------------------------------------------

    public TrackInfo[] getTrackInfo() {
        MediaPlayer.TrackInfo[] tracks;
        try {
            tracks = player.getTrackInfo();
        } catch (RuntimeException e) {
            return new TrackInfo[0];
        }
        if (inbandTracks < 0) inbandTracks = tracks.length;
        TrackInfo[] out = new TrackInfo[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            MediaPlayer.TrackInfo t = tracks[i];
            int type = t.getTrackType();
            if (i >= inbandTracks && (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                    || type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE)) {
                type = outbandSubtitleTracks.contains(i) ? TRACK_OUTBAND_SUBTITLE : TRACK_OUTBAND_TIMEDTEXT;
            }
            boolean video = type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO;
            MediaFormat format = t.getFormat();
            String mime = format != null ? format.getString(MediaFormat.KEY_MIME) : null;
            out[i] = new TrackInfo(type, t.getLanguage(), mime != null ? mime : "",
                    video ? player.getVideoWidth() : 0, video ? player.getVideoHeight() : 0, video ? frameRate : 0);
        }
        return out;
    }

    public void selectTrack(int index) {
        player.selectTrack(index);
    }

    public void deselectTrack(int index) {
        player.deselectTrack(index);
    }

    public void addTimedTextSource(String path, String mimeType) {
        try {
            if (inbandTracks < 0) getTrackInfo();
            player.addTimedTextSource(path, mimeType);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addSubtitleSource(InputStream stream, MediaFormat format) {
        if (inbandTracks < 0) getTrackInfo();
        int before = player.getTrackInfo().length;
        // MediaPlayer's own (hidden) call for WebVTT / TTML files; where it is refused the subtitles stay off.
        try {
            MediaPlayer.class.getMethod("addSubtitleSource", InputStream.class, MediaFormat.class)
                    .invoke(player, stream, format);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "subtitle file not supported: " + e);
            return;
        }
        // MediaPlayer adds the track on its own thread; it will be the next one.
        outbandSubtitleTracks.add(before);
    }

    /** MediaPlayer cannot drop a track added from a file; a new video starts without them. */
    public void removeOutbandTimedTextSources() {
    }

    public void removeOutbandSubtitleSources() {
    }

    public void setSubtitleControllerAndAnchor(Context context, android.widget.VideoView anchor) {
    }

    // ---- frames --------------------------------------------------------------------------------------------------

    public Bitmap getCurrentFrame() {
        return getCurrentFrame(0, 0);
    }

    public Bitmap getCurrentFrame(int option) {
        return getCurrentFrame(0, 0);
    }

    /**
     * The frame on screen (MediaPlayer has no frame grab): copied from the video's surface, or where that fails read
     * from the file at the current position; scaled to fit width x height when given.
     */
    public Bitmap getCurrentFrame(int width, int height) {
        Bitmap frame = copySurface();
        if (frame == null) frame = readFrame();
        if (frame == null || width <= 0 || height <= 0) return frame;
        float scale = Math.min(width / (float) frame.getWidth(), height / (float) frame.getHeight());
        if (scale >= 1f) return frame;
        return Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                Math.max(1, Math.round(frame.getHeight() * scale)), true);
    }

    private Bitmap copySurface() {
        Surface target = surface != null ? surface : holder != null ? holder.getSurface() : null;
        int w = player.getVideoWidth();
        int h = player.getVideoHeight();
        if (target == null || !target.isValid() || w <= 0 || h <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] result = {-1};
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            android.view.PixelCopy.request(target, bitmap, r -> {
                result[0] = r;
                done.countDown();
            }, copyHandler());
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException | RuntimeException e) {
            return null;
        }
        return result[0] == android.view.PixelCopy.SUCCESS ? bitmap : null;
    }

    private static Handler copyThread;

    private static synchronized Handler copyHandler() {
        if (copyThread == null) {
            android.os.HandlerThread thread = new android.os.HandlerThread("SemMediaPlayer frames");
            thread.start();
            copyThread = new Handler(thread.getLooper());
        }
        return copyThread;
    }

    private Bitmap readFrame() {
        if (sourceUri == null && sourceFd == null) return null;
        long at = getCurrentPosition() * 1000L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (sourceFd != null) retriever.setDataSource(sourceFd.getFileDescriptor());
            else retriever.setDataSource(sourceContext, sourceUri);
            return retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing to free.
            }
        }
    }

    // ---- One UI's extras: not on this phone ----------------------------------------------------------------------

    public boolean setParameter(int key, int value) {
        if (key == KEY_SYNC_INIT) syncInit = value == 1;
        return false;
    }

    public boolean setParameter(int key, String value) {
        return false;
    }

    public void crop(int left, int top, int right, int bottom) {
    }

    public int getPlaybackEffect() {
        return 0;
    }

    public void setPlaybackEffect(int effect, int value) {
    }

    public void setPlaybackRange(int start, int end) {
    }

    public void setTemporalZoom(int zoom) {
    }

    public void setAudioVolumeFade(int a, int b, int c, int d, int e) {
    }

    public void setBackgroundMusic(SemBackgroundMusic music) {
    }

    public void setDynamicViewingConfigurations(List configurations) {
    }

    public SuperSlowRegion[] getSuperSlowRegions() {
        return new SuperSlowRegion[0];
    }

    // ---- listeners -----------------------------------------------------------------------------------------------

    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        initListener = listener;
    }

    public void setOnPlaybackCompleteListener(OnPlaybackCompleteListener listener) {
        completeListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        errorListener = listener;
    }

    public void setOnInfoListener(OnInfoListener listener) {
        infoListener = listener;
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        sizeListener = listener;
    }

    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        seekListener = listener;
    }

    public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        bufferingListener = listener;
    }

    public void setOnTimedTextListener(OnTimedTextListener listener) {
        player.setOnTimedTextListener(listener == null ? null : (mp, text) -> listener.onTimedText(this, text));
    }

    public static class DynamicViewingConfiguration {
        public DynamicViewingConfiguration(int width, int height, float ratio) {
        }
    }

    public interface OnBufferingUpdateListener {
        void onBufferingUpdate(SemMediaPlayer player, int percent);
    }

    public interface OnErrorListener {
        boolean onError(SemMediaPlayer player, int what, int extra);
    }

    public interface OnInfoListener {
        boolean onInfo(SemMediaPlayer player, int what, int extra);
    }

    public interface OnInitCompleteListener {
        void onInitComplete(SemMediaPlayer player, TrackInfo[] tracks);
    }

    public interface OnPlaybackCompleteListener {
        void onPlaybackComplete(SemMediaPlayer player);
    }

    public interface OnSeekCompleteListener {
        void onSeekComplete(SemMediaPlayer player);
    }

    public interface OnTimedTextListener {
        void onTimedText(SemMediaPlayer player, TimedText text);
    }

    public interface OnVideoSizeChangedListener {
        void onVideoSizeChanged(SemMediaPlayer player, int width, int height);
    }

    public static class SuperSlowRegion {
        public int getEndTime() {
            return 0;
        }

        public int getRegionType() {
            return 0;
        }

        public int getStartTime() {
            return 0;
        }
    }

    public static class TrackInfo {
        private final int type;
        private final String language;
        private final String name;
        private final int width;
        private final int height;
        private final int frameRate;

        TrackInfo(int type, String language, String name, int width, int height, int frameRate) {
            this.type = type;
            this.language = language;
            this.name = name;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }

        public int getTrackType() {
            return type;
        }

        public String getLanguage() {
            return language;
        }

        public String getName() {
            return name;
        }

        public int getVideoWidth() {
            return width;
        }

        public int getVideoHeight() {
            return height;
        }

        public int getFrameRate() {
            return frameRate;
        }
    }
}
