package com.samsung.android.media;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;

/**
 * Stand-in for One UI's media player under the names Wonderland's video layers use (no Samsung code): Android's own
 * MediaPlayer plays the video. init(...) prepares in the background and reports through OnInitCompleteListener, like
 * One UI's player; getTrackInfo() answers the video size for every track index the caller reads.
 */
public class SemMediaPlayer {
    private static final String TAG = "UA";
    private final MediaPlayer player = new MediaPlayer();
    private OnInitCompleteListener initListener;
    private OnPlaybackCompleteListener completeListener;
    private OnErrorListener errorListener;
    private OnSeekCompleteListener seekListener;

    public SemMediaPlayer() {
        player.setOnPreparedListener(mp -> {
            if (initListener != null) initListener.onInitComplete(this, getTrackInfo());
        });
        player.setOnCompletionListener(mp -> {
            if (completeListener != null) completeListener.onPlaybackComplete(this);
        });
        player.setOnErrorListener((mp, what, extra) -> errorListener != null && errorListener.onError(this, what, extra));
        player.setOnSeekCompleteListener(mp -> {
            if (seekListener != null) seekListener.onSeekComplete(this);
        });
    }

    private void failed(Exception e) {
        Log.w(TAG, "Wonderland video: " + e);
        if (errorListener != null) errorListener.onError(this, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
    }

    public void init(Context context, Uri uri) {
        try {
            player.setDataSource(context, uri);
            player.prepareAsync();
        } catch (Exception e) {
            failed(e);
        }
    }

    public void init(FileDescriptor fd) {
        try {
            player.setDataSource(fd);
            player.prepareAsync();
        } catch (Exception e) {
            failed(e);
        }
    }

    public void init(FileDescriptor fd, long offset, long length) {
        try {
            player.setDataSource(fd, offset, length);
            player.prepareAsync();
        } catch (Exception e) {
            failed(e);
        }
    }

    public int getCurrentPosition() {
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public TrackInfo[] getTrackInfo() {
        int width = 0;
        int height = 0;
        try {
            width = player.getVideoWidth();
            height = player.getVideoHeight();
        } catch (IllegalStateException e) {
            // Not prepared yet.
        }
        return new TrackInfo[] { new TrackInfo(width, height), new TrackInfo(width, height) };
    }

    public boolean isLooping() {
        return player.isLooping();
    }

    public boolean isPlaying() {
        try {
            return player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public void pause() {
        try {
            player.pause();
        } catch (IllegalStateException e) {
            // Not playing.
        }
    }

    public void release() {
        player.release();
    }

    public void reset() {
        player.reset();
    }

    public void seekTo(int msec) {
        try {
            player.seekTo(msec);
        } catch (IllegalStateException e) {
            // Not prepared.
        }
    }

    public void setLooping(boolean looping) {
        player.setLooping(looping);
    }

    public void setOnErrorListener(OnErrorListener listener) {
        errorListener = listener;
    }

    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        initListener = listener;
    }

    public void setOnPlaybackCompleteListener(OnPlaybackCompleteListener listener) {
        completeListener = listener;
    }

    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        seekListener = listener;
    }

    /** One UI's own playback parameters: none here. */
    public boolean setParameter(int key, int value) {
        return false;
    }

    public void setSurface(Surface surface) {
        player.setSurface(surface);
    }

    public void setVolume(float left, float right) {
        player.setVolume(left, right);
    }

    public void start() {
        try {
            player.start();
        } catch (IllegalStateException e) {
            failed(e);
        }
    }

    public interface OnErrorListener {
        boolean onError(SemMediaPlayer player, int what, int extra);
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

    public static class TrackInfo {
        private final int width;
        private final int height;

        TrackInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getVideoHeight() {
            return height;
        }

        public int getVideoWidth() {
            return width;
        }
    }
}
