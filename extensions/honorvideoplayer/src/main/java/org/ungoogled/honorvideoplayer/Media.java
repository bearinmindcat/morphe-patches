package org.ungoogled.honorvideoplayer;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

/**
 * One media source (a file path, a content Uri or an open file descriptor) as Android's media framework reads it: the
 * metadata retriever and extractor the stand-ins for Honor's media library (AVFoundationPlayer, AVFoundationExtractor)
 * answer from. Both are opened on first use and closed with the source.
 */
public final class Media {
    public static final String TAG = "HonorMedia";

    private String path;
    private Context context;
    private Uri uri;
    private ParcelFileDescriptor descriptor;
    private long offset;
    private MediaMetadataRetriever retriever;
    private MediaExtractor extractor;
    private boolean extractorFailed;

    public void set(String path) {
        close();
        this.path = path;
    }

    public void set(Context context, Uri uri) {
        close();
        this.context = context;
        this.uri = uri;
    }

    /** The caller's descriptor stays the caller's: this keeps its own duplicate. */
    public void set(int fd, long offset) throws IOException {
        close();
        this.descriptor = ParcelFileDescriptor.fromFd(fd);
        this.offset = Math.max(0, offset);
    }

    public boolean isSet() {
        return path != null || uri != null || descriptor != null;
    }

    public ParcelFileDescriptor descriptor() {
        return descriptor;
    }

    public long offset() {
        return offset;
    }

    /** The metadata retriever for this source, or null when it cannot read it. */
    public MediaMetadataRetriever retriever() {
        if (retriever != null || !isSet()) return retriever;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (path != null) r.setDataSource(path);
            else if (uri != null) r.setDataSource(context, uri);
            else r.setDataSource(descriptor.getFileDescriptor(), offset, 0x7ffffffffffffffL);
            retriever = r;
        } catch (RuntimeException e) {
            Log.w(TAG, "metadata: " + e);
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing to release
            }
        }
        return retriever;
    }

    /** The extractor for this source (its tracks, as the container has them). */
    public MediaExtractor extractor() throws IOException {
        if (extractor != null) return extractor;
        if (!isSet() || extractorFailed) throw new IOException("no media");
        MediaExtractor e = new MediaExtractor();
        try {
            if (path != null) e.setDataSource(path);
            else if (uri != null) e.setDataSource(context, uri, null);
            else e.setDataSource(descriptor.getFileDescriptor(), offset, 0x7ffffffffffffffL);
        } catch (IOException | RuntimeException failure) {
            extractorFailed = true;
            e.release();
            throw failure instanceof IOException ? (IOException) failure : new IOException(failure);
        }
        extractor = e;
        return extractor;
    }

    public String metadata(int key) {
        MediaMetadataRetriever r = retriever();
        try {
            return r == null ? null : r.extractMetadata(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int metadataInt(int key, int def) {
        String value = metadata(key);
        try {
            return value == null ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The first video track's format, or null. */
    public MediaFormat videoFormat() {
        try {
            MediaExtractor e = extractor();
            for (int i = 0; i < e.getTrackCount(); i++) {
                MediaFormat f = e.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return f;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "video format: " + e);
        }
        return null;
    }

    /** Frames per second of the video track (the container's figure, else frames over duration), 0 if unknown. */
    public int frameRate() {
        MediaFormat f = videoFormat();
        if (f != null && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                return f.getInteger(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException e) {
                return Math.round(f.getFloat(MediaFormat.KEY_FRAME_RATE));
            }
        }
        int frames = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, 0);
        int ms = metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION, 0);
        return frames > 0 && ms > 0 ? Math.round(frames * 1000f / ms) : 0;
    }

    /** Whether the video is HDR (PQ or HLG transfer). */
    public boolean hdr() {
        MediaFormat f = videoFormat();
        if (f != null && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            int transfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
            return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG;
        }
        return false;
    }

    public void close() {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
            retriever = null;
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // already closed
            }
            descriptor = null;
        }
        extractorFailed = false;
        path = null;
        uri = null;
        context = null;
        offset = 0;
    }

    /**
     * Android's language code as Honor's library gives it: empty when the container has none -- "und", or "```" for an
     * MP4 whose language field is zero (the app shows "Audio track 1" then, instead of a code).
     */
    public static String language(String language) {
        if (language == null) return "";
        String code = language.trim();
        return code.equals("und") || code.matches("`+") ? "" : code;
    }
}
