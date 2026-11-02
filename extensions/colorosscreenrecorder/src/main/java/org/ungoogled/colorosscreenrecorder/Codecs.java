package org.ungoogled.colorosscreenrecorder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/**
 * Which video codec the recorder encodes with. ColorOS's recorder offers (and uses) H.265 whenever the phone has any
 * HEVC encoder, true on OPPO and OnePlus phones because they encode HEVC in hardware. But every Android 10+ phone also carries
 * Google's software HEVC encoder (c2.android.hevc.encoder), which takes at most 512x512 -- far below a screen -- so on a
 * phone without a hardware HEVC encoder an H.265 recording crashed the recorder. H.265 is now offered only when a
 * hardware encoder does it; otherwise the recorder uses H.264 (its own fallback).
 */
public final class Codecs {
    private Codecs() {
    }

    public static boolean hevc() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
            for (String type : info.getSupportedTypes()) {
                if ("video/hevc".equalsIgnoreCase(type)) return true;
            }
        }
        return false;
    }
}
