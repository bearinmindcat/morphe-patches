package com.hihonor.mcs.media.avfoundation.player;

import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;

import org.ungoogled.honorvideoplayer.Media;

import java.io.IOException;

/**
 * Stand-in for Honor's media library player (com.hihonor.mcs.media.avfoundation, a MagicOS system library that Video
 * Player requires), with its names and signatures, played by Android's own MediaPlayer. It reports what the library
 * reports through its listener: playback states as events 2..8 (preparing, prepared, started, paused, stopped,
 * completed, seek completed), the video size, info codes (3 = first frame shown), errors (MediaPlayer's codes, which
 * the app already knows: -1004 I/O, -1007 malformed, -1010 unsupported) and timed text (event 20099). Durations are in
 * microseconds and positions in milliseconds, as the library's. On an Honor phone the library's own class loads first,
 * so this never runs there.
 */
public class AVFoundationPlayer {
    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PREPARED = 3;
    private static final int STATE_STARTED = 4;
    private static final int STATE_PAUSED = 5;
    private static final int STATE_STOPPED = 6;
    private static final int STATE_COMPLETED = 7;
    private static final int STATE_SEEK_COMPLETED = 8;
    private static final int STATE_ERROR = -1;
    private static final int EVENT_TIMED_TEXT = 20099;

    // AVTrackInfo's track types
    private static final int TYPE_UNKNOWN = -1;
    private static final int TYPE_VIDEO = 0;
    private static final int TYPE_AUDIO = 1;
    private static final int TYPE_DATA = 2;
    private static final int TYPE_SUBTITLE = 3;

    private final MediaPlayer player = new MediaPlayer();
    private final Media media = new Media();
    private AVFoundationPlayerListener listener;
    private int state = STATE_IDLE;
    private boolean prepared;
    private float speed = 1f;
    private float volume = 1f;
    private Surface surface;
    private boolean videoDisabled;
    /** The video went to no surface after preparing (background play, a surface being replaced). */
    private boolean surfaceDetached;

    public interface AVFoundationPlayerListener {
        void onError(int code, String message);

        void onEvent(int event, int arg1, int arg2, Object object);

        void onInfo(int code, String message);

        void onVideoSizeChanged(int width, int height);

        void onVolumeChanged(float volume);
    }

    public AVFoundationPlayer() {
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        player.setOnPreparedListener(mp -> {
            prepared = true;
            state(STATE_PREPARED);
        });
        player.setOnCompletionListener(mp -> {
            if (!mp.isLooping()) state(STATE_COMPLETED);
        });
        player.setOnSeekCompleteListener(mp -> event(STATE_SEEK_COMPLETED, 0, 0, ""));
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (listener != null) listener.onVideoSizeChanged(width, height);
        });
        player.setOnInfoListener((mp, what, extra) -> {
            if (listener != null) listener.onInfo(what, String.valueOf(extra));
            return true;
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(Media.TAG, "playback error " + what + "/" + extra);
            state = STATE_ERROR;
            prepared = false;
            error(code(what, extra), "MediaPlayer error " + what + "/" + extra);
            return true;
        });
        player.setOnTimedTextListener((mp, text) ->
                event(EVENT_TIMED_TEXT, 0, 0, text == null || text.getText() == null ? "" : text.getText()));
    }

    /** MediaPlayer's error codes as the library's (which uses the same I/O, malformed and unsupported codes). */
    private static int code(int what, int extra) {
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                return extra;
            default:
                return what == MediaPlayer.MEDIA_ERROR_SERVER_DIED ? -1001 : MediaPlayer.MEDIA_ERROR_UNSUPPORTED;
        }
    }

    private void event(int event, int arg1, int arg2, Object object) {
        if (listener != null) listener.onEvent(event, arg1, arg2, object);
    }

    private void state(int newState) {
        state = newState;
        event(newState, 0, 0, "");
    }

    private void error(int code, String message) {
        if (listener != null) listener.onError(code, message);
    }

    public void setListener(com.hihonor.mcs.media.avfoundation.player.AVFoundationPlayer.AVFoundationPlayerListener l) {
        listener = l;
    }

    // Source

    private void beforeSource() {
        if (state != STATE_IDLE) {
            player.reset();
            prepared = false;
            surfaceDetached = false;
            state = STATE_IDLE;
        }
    }

    public void setDataSource(java.lang.String path) {
        beforeSource();
        try {
            player.setDataSource(path);
            media.set(path);
        } catch (IOException | RuntimeException e) {
            Log.w(Media.TAG, "source: " + e);
            error(MediaPlayer.MEDIA_ERROR_IO, String.valueOf(e.getMessage()));
        }
    }

    public void setDataSource(android.net.Uri uri, android.content.Context context) {
        beforeSource();
        try {
            player.setDataSource(context, uri);
            media.set(context, uri);
        } catch (IOException | RuntimeException e) {
            Log.w(Media.TAG, "source: " + e);
            error(-2004, String.valueOf(e.getMessage()));
        }
    }

    public void setDataSource(int fd, java.lang.Long offset) {
        beforeSource();
        try {
            media.set(fd, offset == null ? 0 : offset);
            player.setDataSource(media.descriptor().getFileDescriptor(), media.offset(), 0x7ffffffffffffffL);
        } catch (IOException | RuntimeException e) {
            Log.w(Media.TAG, "source: " + e);
            error(MediaPlayer.MEDIA_ERROR_IO, String.valueOf(e.getMessage()));
        }
    }

    // Transport

    public void prepare() {
        try {
            state(STATE_PREPARING);
            player.prepareAsync();
        } catch (IllegalStateException e) {
            error(MediaPlayer.MEDIA_ERROR_UNSUPPORTED, "prepare: " + e.getMessage());
        }
    }

    public void start() {
        if (!prepared) return;
        try {
            player.start();
            if (speed != 1f) player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            state(STATE_STARTED);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(Media.TAG, "start: " + e);
        }
    }

    public void pause() {
        if (!prepared) return;
        try {
            player.pause();
            state(STATE_PAUSED);
        } catch (IllegalStateException e) {
            Log.w(Media.TAG, "pause: " + e);
        }
    }

    public void stop() {
        try {
            player.stop();
        } catch (IllegalStateException e) {
            Log.w(Media.TAG, "stop: " + e);
        }
        prepared = false;
        state(STATE_STOPPED);
    }

    public void release() {
        prepared = false;
        player.release();
        media.close();
    }

    /** Milliseconds; the seek modes are MediaPlayer's (previous sync, next sync, closest sync, closest). */
    public void seekTo(long position, int mode) {
        if (!prepared) return;
        try {
            player.seekTo(position, mode >= MediaPlayer.SEEK_PREVIOUS_SYNC && mode <= MediaPlayer.SEEK_CLOSEST ? mode : MediaPlayer.SEEK_CLOSEST_SYNC);
        } catch (IllegalStateException e) {
            Log.w(Media.TAG, "seek: " + e);
        }
    }

    public boolean isPlaying() {
        try {
            return prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Microseconds, as Honor's library. */
    public long getDuration() {
        try {
            return prepared ? Math.max(0, player.getDuration()) * 1000L : 0L;
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    /** Milliseconds. */
    public long getCurrentPosition() {
        try {
            return prepared || state == STATE_COMPLETED ? player.getCurrentPosition() : 0L;
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    public void setLooping(boolean looping) {
        player.setLooping(looping);
    }

    public boolean isLooping() {
        return player.isLooping();
    }

    public void setVolume(float v) {
        volume = v;
        player.setVolume(v, v);
        if (listener != null) listener.onVolumeChanged(v);
    }

    public float getVolume() {
        return volume;
    }

    /** Applied at once while playing; otherwise at the next start (MediaPlayer would start playback to apply it). */
    public void setPlaybackSpeed(float s) {
        speed = s;
        try {
            if (isPlaying()) player.setPlaybackParams(player.getPlaybackParams().setSpeed(s));
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(Media.TAG, "speed: " + e);
        }
    }

    public float getPlaybackSpeed() {
        return speed;
    }

    public int getAudioSessionId() {
        return player.getAudioSessionId();
    }

    // Video output

    public void setAndroidSurface(android.view.Surface s) {
        surface = s;
        if (!videoDisabled) output(s);
    }

    public void clearAndroidSurface(android.view.Surface s) {
        if (s == null || s == surface) {
            surface = null;
            output(null);
        }
    }

    /** Audio only (background play): the video goes nowhere until it is enabled again. */
    public int disableVideo(boolean disabled) {
        videoDisabled = disabled;
        output(disabled ? null : surface);
        return 0;
    }

    /**
     * Where the video goes. MediaPlayer drops its video decoder when the surface goes away mid-playback and, given a
     * surface again, decodes nothing more until it seeks (measured: a video-only file stayed black and stuck at its
     * position): so a surface that comes back after one was lost seeks to where the playback is.
     */
    private void output(Surface s) {
        try {
            player.setSurface(s);
            if (s == null) {
                if (prepared) surfaceDetached = true;
            } else if (surfaceDetached && prepared) {
                surfaceDetached = false;
                player.seekTo(player.getCurrentPosition(), MediaPlayer.SEEK_CLOSEST);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(Media.TAG, "surface: " + e);
        }
    }

    public int getVideoWidth() {
        return player.getVideoWidth();
    }

    public int getVideoHeight() {
        return player.getVideoHeight();
    }

    public float getRotation() {
        return media.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
    }

    public int getFrameRate() {
        return media.frameRate();
    }

    public boolean isHdrVideo() {
        return media.hdr();
    }

    /** The frame on screen (for a screenshot). */
    public android.graphics.Bitmap getCurrentPicture() {
        MediaMetadataRetriever r = media.retriever();
        if (r == null) return null;
        try {
            Bitmap frame = r.getFrameAtTime(getCurrentPosition() * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            return frame != null ? frame : r.getFrameAtTime(getCurrentPosition() * 1000L);
        } catch (RuntimeException e) {
            Log.w(Media.TAG, "picture: " + e);
            return null;
        }
    }

    // Tracks (indices are MediaPlayer's)

    private MediaPlayer.TrackInfo[] tracks() {
        try {
            return prepared ? player.getTrackInfo() : new MediaPlayer.TrackInfo[0];
        } catch (RuntimeException e) {
            return new MediaPlayer.TrackInfo[0];
        }
    }

    private MediaPlayer.TrackInfo track(int index) {
        MediaPlayer.TrackInfo[] all = tracks();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    private static int type(MediaPlayer.TrackInfo t) {
        if (t == null) return TYPE_UNKNOWN;
        switch (t.getTrackType()) {
            case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO: return TYPE_VIDEO;
            case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO: return TYPE_AUDIO;
            // In-band timed text MediaPlayer delivers as text; other subtitle kinds need a renderer it lacks here.
            case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT: return TYPE_SUBTITLE;
            case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE:
            case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_METADATA: return TYPE_DATA;
            default: return TYPE_UNKNOWN;
        }
    }

    private static String mime(MediaPlayer.TrackInfo t) {
        MediaFormat f = t == null ? null : t.getFormat();
        String mime = f == null ? null : f.getString(MediaFormat.KEY_MIME);
        return mime == null ? "" : mime;
    }

    public int getTrackCount() {
        return tracks().length;
    }

    public int getTrackId(int index) {
        return index;
    }

    public int getSelectTrackType(int index) {
        return type(track(index));
    }

    public java.lang.String getLanguage(int index) {
        MediaPlayer.TrackInfo t = track(index);
        return Media.language(t == null ? null : t.getLanguage());
    }

    public java.lang.String getVideoMime(int index) {
        return mime(track(index));
    }

    public java.lang.String getAudioMime(int index) {
        return mime(track(index));
    }

    public int getAudioTrackCount() {
        int count = 0;
        for (MediaPlayer.TrackInfo t : tracks()) if (type(t) == TYPE_AUDIO) count++;
        return count;
    }

    /** The selected track of a type (AVTrackInfo's types), -1 if none. */
    public int getSelectTrack(int type) {
        int androidType;
        switch (type) {
            case TYPE_VIDEO: androidType = MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO; break;
            case TYPE_AUDIO: androidType = MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO; break;
            case TYPE_SUBTITLE: androidType = MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT; break;
            default: return -1;
        }
        try {
            return prepared ? player.getSelectedTrack(androidType) : -1;
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    public int selectTrack(int index) {
        try {
            player.selectTrack(index);
            return 0;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(Media.TAG, "track " + index + ": " + e);
            return -38;
        }
    }
}
