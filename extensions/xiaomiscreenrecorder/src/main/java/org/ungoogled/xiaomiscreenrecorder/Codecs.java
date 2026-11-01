package org.ungoogled.xiaomiscreenrecorder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/**
 * Which video codec the recorder encodes with. Xiaomi's answer is "HEVC whenever the phone has any HEVC encoder",
 * true on every Xiaomi phone because they all encode HEVC in hardware. But every Android 10+ phone also carries
 * Google's software HEVC encoder (c2.android.hevc.encoder), which takes at most 512x512 -- far below a screen -- so a
 * phone without a hardware HEVC encoder could never record. HEVC is now picked only when a hardware encoder does it;
 * otherwise the recorder uses H.264 (its own fallback).
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
