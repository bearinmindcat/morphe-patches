package com.samsung.android.media.vision;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Photo Editor uses, answering "absent" off One UI (no Samsung code). */
public class SemSegmentation {
    public SemSegmentation() {
    }
    public static com.samsung.android.media.vision.SemSegmentation getInstance(android.graphics.Bitmap p0) {
        return new com.samsung.android.media.vision.SemSegmentation();
    }
    public boolean process(com.samsung.android.media.vision.SemSegmentation.Mode p0, int[] p1) {
        return false;
    }
    public void release() {
    }
    public static class Mode {
        public static com.samsung.android.media.vision.SemSegmentation.Mode AUTO_WITH_FG_LOOP_MARKER;
        public static com.samsung.android.media.vision.SemSegmentation.Mode MANUAL_WITH_BG_LOOP_MARKER;
        public static com.samsung.android.media.vision.SemSegmentation.Mode MANUAL_WITH_FG_LOOP_MARKER;
    }
    public static class Result {
        public static android.graphics.Bitmap maskBitmap;
        public static java.util.ArrayList segmentContour;
    }
}
