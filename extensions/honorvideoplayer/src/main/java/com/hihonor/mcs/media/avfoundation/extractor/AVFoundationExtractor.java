package com.hihonor.mcs.media.avfoundation.extractor;

import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.ungoogled.honorvideoplayer.Media;

import java.io.IOException;

/**
 * Stand-in for Honor's media library extractor (com.hihonor.mcs.media.avfoundation, a MagicOS system library), with
 * its names and signatures, answered by Android's own MediaExtractor and MediaMetadataRetriever: tracks (type, mime,
 * language, video size), duration in microseconds, rotation and a thumbnail. On an Honor phone the library's own class
 * loads first, so this never runs there. Video Player's library scan reuses one extractor for many files.
 */
public class AVFoundationExtractor {
    // AVTrackInfo's track types
    private static final int TYPE_VIDEO = 0;
    private static final int TYPE_AUDIO = 1;
    private static final int TYPE_DATA = 2;
    private static final int TYPE_SUBTITLE = 3;

    private final Media media = new Media();

    public AVFoundationExtractor() {
    }

    public void setDataSource(java.lang.String path) {
        media.set(path);
        open();
    }

    public void setDataSource(int fd, long offset) {
        try {
            media.set(fd, offset);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot open descriptor " + fd, e);
        }
        open();
    }

    private void open() {
        try {
            media.extractor();
        } catch (IOException e) {
            Log.w(Media.TAG, "extractor: " + e);
            throw new IllegalArgumentException("unsupported media", e);
        }
    }

    private MediaFormat format(int track) {
        try {
            MediaExtractor e = media.extractor();
            return track >= 0 && track < e.getTrackCount() ? e.getTrackFormat(track) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public int getTrackCount() {
        try {
            return media.extractor().getTrackCount();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public java.lang.String getTrackMime(int track) {
        MediaFormat f = format(track);
        String mime = f == null ? null : f.getString(MediaFormat.KEY_MIME);
        return mime == null ? "" : mime;
    }

    public int getTrackType(int track) {
        String mime = getTrackMime(track);
        if (mime.startsWith("video/")) return TYPE_VIDEO;
        if (mime.startsWith("audio/")) return TYPE_AUDIO;
        if (mime.startsWith("text/") || mime.equals("application/x-subrip") || mime.equals("application/ttml+xml")
                || mime.equals("application/x-quicktime-tx3g")) return TYPE_SUBTITLE;
        return TYPE_DATA;
    }

    public java.lang.String getLanguage(int track) {
        MediaFormat f = format(track);
        return Media.language(f == null ? null : f.getString(MediaFormat.KEY_LANGUAGE));
    }

    /** A track's size; a track that is not video answers the first video track's. */
    private int size(int track, String key) {
        MediaFormat f = format(track);
        String mime = f == null ? null : f.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("video/")) f = media.videoFormat();
        return f != null && f.containsKey(key) ? f.getInteger(key) : 0;
    }

    public int getWidth(int track) {
        return size(track, MediaFormat.KEY_WIDTH);
    }

    public int getHeight(int track) {
        return size(track, MediaFormat.KEY_HEIGHT);
    }

    /** The longest track's duration, in microseconds (as Honor's library). */
    public long getDuration() {
        long longest = 0;
        for (int i = 0; i < getTrackCount(); i++) {
            MediaFormat f = format(i);
            if (f != null && f.containsKey(MediaFormat.KEY_DURATION)) longest = Math.max(longest, f.getLong(MediaFormat.KEY_DURATION));
        }
        if (longest > 0) return longest;
        return media.metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION, 0) * 1000L;
    }

    public float getRotation() {
        return media.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
    }

    public android.graphics.Bitmap getThumbnail(int width, int height) {
        MediaMetadataRetriever r = media.retriever();
        if (r == null) return null;
        try {
            Bitmap frame = width > 0 && height > 0
                    ? r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                    : r.getFrameAtTime();
            return frame;
        } catch (RuntimeException e) {
            Log.w(Media.TAG, "thumbnail: " + e);
            return null;
        }
    }

    public void release() {
        media.close();
    }
}
