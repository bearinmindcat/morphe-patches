package com.samsung.android.transcode.constants;

import java.util.ArrayList;
import java.util.Arrays;

/** One UI's transcoder constants: the container types the editor accepts (MP4 family, as Android's muxer writes). */
public class EncodeConstants {
    public static class ContentType {
        public static ArrayList sSupportedVideoTypes = new ArrayList<>(Arrays.asList(
                "video/mp4", "video/3gpp", "video/3gpp2", "video/quicktime", "video/x-matroska", "video/webm"));
    }
}
