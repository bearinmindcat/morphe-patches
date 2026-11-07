package com.samsung.android.media.vision;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Capture uses, answering "absent" off One UI (no Samsung code). */
public class SemSegmentation {
    public SemSegmentation() {
    }
    public boolean canRedo() {
        return false;
    }
    public boolean canUndo() {
        return false;
    }
    public static com.samsung.android.media.vision.SemSegmentation getInstance(android.graphics.Bitmap p0) {
        return new com.samsung.android.media.vision.SemSegmentation();
    }
    public boolean process(com.samsung.android.media.vision.SemSegmentation.Mode p0, int[] p1) {
        return false;
    }
    public void redo() {
    }
    public void release() {
    }
    public void reset() {
    }
    public void undo() {
    }
    public static class Mode {
        public static com.samsung.android.media.vision.SemSegmentation.Mode AUTO_WITH_FG_LOOP_MARKER;
        public static com.samsung.android.media.vision.SemSegmentation.Mode MANUAL_WITH_BG_STROKE_MARKER;
        public static com.samsung.android.media.vision.SemSegmentation.Mode MANUAL_WITH_FG_STROKE_MARKER;
        public static com.samsung.android.media.vision.SemSegmentation.Mode NONE;
        public int ordinal() {
            return 0;
        }
        public static com.samsung.android.media.vision.SemSegmentation.Mode[] values() {
            return new com.samsung.android.media.vision.SemSegmentation.Mode[0];
        }
    }
    public static class Result {
        public static android.graphics.Bitmap maskBitmap;
        public static java.util.ArrayList segmentContour;
    }
}
