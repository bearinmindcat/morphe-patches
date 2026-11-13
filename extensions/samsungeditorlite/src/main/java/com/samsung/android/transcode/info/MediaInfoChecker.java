package com.samsung.android.transcode.info;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.Arrays;
import java.util.List;

/**
 * One UI's checks before an edit: is the file a video this phone decodes, can it be cut without re-encoding (an MP4
 * family file whose tracks Android's MP4 muxer takes as they are), and is its size one the phone's codecs handle.
 */
public class MediaInfoChecker {
    private static final List<String> REWRITE_CONTAINERS = Arrays.asList("video/mp4", "video/3gpp", "video/3gpp2", "video/quicktime");
    private static final List<String> MUXER_VIDEO = Arrays.asList(
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263, MediaFormat.MIMETYPE_VIDEO_AV1);
    private static final List<String> MUXER_AUDIO = Arrays.asList(
            MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB);

    public static boolean isSupportedFileFormat(MediaInfo.MediaFileInfo info) {
        return info != null && info.HasVideo && info.Width > 0 && info.Height > 0
                && (info.VideoMime == null || hasDecoder(info.VideoMime));
    }

    public static boolean isRewriteSupportedFileFormat(MediaInfo.MediaFileInfo info) {
        return isSupportedFileFormat(info) && info.MimeType != null && REWRITE_CONTAINERS.contains(info.MimeType);
    }

    /** Whether both tracks can go into Android's MP4 muxer unchanged (no audio track is fine). */
    public static boolean isRewritable(MediaFormat audio, MediaFormat video) {
        if (video == null || !MUXER_VIDEO.contains(video.getString(MediaFormat.KEY_MIME))) return false;
        return audio == null || MUXER_AUDIO.contains(audio.getString(MediaFormat.KEY_MIME));
    }

    /** Whether the video's decoder takes width x height (the target size only needs to be positive). */
    public static boolean isSupportedResolution(MediaFormat video, int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0) return false;
        if (video == null) return true;
        String mime = video.getString(MediaFormat.KEY_MIME);
        if (mime == null) return true;
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    MediaCodecInfo.VideoCapabilities caps = codec.getCapabilitiesForType(type).getVideoCapabilities();
                    if (caps == null || caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width)) return true;
                }
            }
        } catch (RuntimeException e) {
            return true;
        }
        return false;
    }

    private static boolean hasDecoder(String mime) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) return true;
            }
        } catch (RuntimeException e) {
            return true;
        }
        return false;
    }
}
