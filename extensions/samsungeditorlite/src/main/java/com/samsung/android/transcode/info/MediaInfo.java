package com.samsung.android.transcode.info;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

/**
 * One UI's media inspector, on Android's MediaExtractor and MediaMetadataRetriever: the video's size and rotation,
 * its container type, its first video or audio track's format, and the frame rate / key-frame spacing of the video
 * last inspected.
 */
public class MediaInfo {
    private static volatile int frameRate = 30;
    private static volatile int frameIntervalMs = 1000;

    public static MediaFileInfo getFileInfo(String path, Context context, Uri uri) {
        MediaFileInfo info = new MediaFileInfo();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (path != null) retriever.setDataSource(path);
            else retriever.setDataSource(context, uri);
            info.Width = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            info.Height = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            info.Rotation = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            info.Duration = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            info.MimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            info.HasVideo = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
        } catch (RuntimeException e) {
            Log.w("UA", "media info: " + e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to free.
            }
        }
        MediaFormat video = getTrackInfo(path, context, uri, true);
        if (video != null) {
            info.VideoMime = video.getString(MediaFormat.KEY_MIME);
            if (info.Width <= 0 && video.containsKey(MediaFormat.KEY_WIDTH)) info.Width = video.getInteger(MediaFormat.KEY_WIDTH);
            if (info.Height <= 0 && video.containsKey(MediaFormat.KEY_HEIGHT)) info.Height = video.getInteger(MediaFormat.KEY_HEIGHT);
            info.HasVideo = true;
        }
        return info;
    }

    /** The first video (video = true) or audio track's format, or null. */
    public static MediaFormat getTrackInfo(String path, Context context, Uri uri, boolean video) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (path != null) extractor.setDataSource(path);
            else extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith(video ? "video/" : "audio/")) continue;
                if (video) readVideoTiming(extractor, i, format);
                return format;
            }
        } catch (Exception e) {
            Log.w("UA", "track info: " + e);
        } finally {
            extractor.release();
        }
        return null;
    }

    private static void readVideoTiming(MediaExtractor extractor, int track, MediaFormat format) {
        int rate = 30;
        try {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    rate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException e) {
                    rate = Math.round(format.getFloat(MediaFormat.KEY_FRAME_RATE));
                }
            }
        } catch (RuntimeException ignored) {
            // Keep 30.
        }
        frameRate = rate > 0 && rate <= 960 ? rate : 30;
        // The spacing of the first two key frames: how far a cut may land from where it was asked.
        try {
            extractor.selectTrack(track);
            long first = -1;
            int samples = 0;
            while (samples++ < 2000) {
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    long t = extractor.getSampleTime();
                    if (first < 0) first = t;
                    else if (t > first) {
                        frameIntervalMs = (int) Math.max(1, (t - first) / 1000);
                        return;
                    }
                }
                if (!extractor.advance()) break;
            }
        } catch (RuntimeException ignored) {
            // Keep the last answer.
        }
    }

    public static int getVideoFramerate() {
        return frameRate;
    }

    public static int getVideoFrameInterval() {
        return frameIntervalMs;
    }

    private static int number(String value) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class MediaFileInfo {
        public int Width;
        public int Height;
        public int Rotation;
        public int Duration;
        public String MimeType;
        public String VideoMime;
        public boolean HasVideo;
    }
}
