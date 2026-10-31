package android.view;

/**
 * Stand-in for One UI's SemBlurInfo (names and signatures Samsung Reminder uses, no Samsung code). Off One UI there is
 * no blur behind a view; the corner radii are kept so that a plain background can stand in (see Blur).
 */
public class SemBlurInfo {
    /** Top-left, top-right, bottom-right, bottom-left; null when none was set. */
    public float[] ungoogledCornerRadii;

    public static class Builder {
        private final android.view.SemBlurInfo info = new android.view.SemBlurInfo();

        public Builder(int p0) {
        }
        public android.view.SemBlurInfo build() {
            return info;
        }
        public android.view.SemBlurInfo.Builder setBackgroundCornerRadius(float p0) {
            info.ungoogledCornerRadii = new float[]{p0, p0, p0, p0};
            return this;
        }
        public android.view.SemBlurInfo.Builder setBackgroundCornerRadius(float p0, float p1, float p2, float p3) {
            info.ungoogledCornerRadii = new float[]{p0, p1, p2, p3};
            return this;
        }
        public android.view.SemBlurInfo.Builder setBitmap(android.graphics.Bitmap p0) {
            return this;
        }
        public android.view.SemBlurInfo.Builder setCanvasScale(int p0) {
            return this;
        }
        public android.view.SemBlurInfo.Builder setColorCurve(float p0, float p1, float p2, float p3, float p4, float p5) {
            return this;
        }
        public android.view.SemBlurInfo.Builder setColorCurvePreset(int p0) {
            return this;
        }
        public android.view.SemBlurInfo.Builder setRadius(int p0) {
            return this;
        }
    }
}
