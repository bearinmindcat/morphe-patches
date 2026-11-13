package com.samsung.android.transcode.util;

/**
 * One UI's helper for Samsung's slow-motion / hyperlapse videos (SEF data that only Samsung's media framework reads).
 * Elsewhere no video is one of them; the speeds are the plain playback-rate factors.
 */
public class SEFHelper {
    public static Speed getSpeed(int value) {
        switch (value) {
            case 2: return Speed.HALF;
            case 4: return Speed.QUARTER;
            case 8: return Speed.EIGHTH;
            case 16: return Speed.SIXTEENTH;
            case 32: return Speed.THIRTY_SECOND;
            default: return Speed.NORMAL;
        }
    }

    public static float getTimeScale(Speed speed) {
        return speed != null ? speed.scale : 1f;
    }

    public static boolean isSEFVideoMode(int mode) {
        return false;
    }

    public static class Speed {
        public static final Speed NORMAL = new Speed(1f);
        public static final Speed HALF = new Speed(2f);
        public static final Speed QUARTER = new Speed(4f);
        public static final Speed EIGHTH = new Speed(8f);
        public static final Speed SIXTEENTH = new Speed(16f);
        public static final Speed THIRTY_SECOND = new Speed(32f);

        final float scale;

        Speed(float scale) {
            this.scale = scale;
        }
    }
}
