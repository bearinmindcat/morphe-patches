package com.samsung.android.transcode.util;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;

/** One UI's transcoder helpers, on Android's MediaExtractor. */
public class CodecsHelper {
    public static MediaExtractor createExtractor(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        return extractor;
    }

    public static MediaExtractor createExtractor(String path) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(path);
        return extractor;
    }

    /** Selects the first video track and returns its index, or -1. */
    public static int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                extractor.selectTrack(i);
                return i;
            }
        }
        return -1;
    }

    /** A bit rate for H.264 at this size (bit/s): 0.12 bit per pixel at 30 frames a second. */
    public static int suggestBitRate(int width, int height) {
        long pixels = (long) Math.max(width, 1) * Math.max(height, 1);
        long rate = pixels * 30L * 12 / 100;
        return (int) Math.max(64_000L, Math.min(rate, 20_000_000L));
    }
}
