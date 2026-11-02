package com.oplus.screenshot;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Screen Recording uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusScreenCapture {
    public OplusScreenCapture() {
    }
    public com.oplus.screenshot.OplusScreenCapture.ScreenshotHardwareBuffer captureDisplay(com.oplus.screenshot.OplusScreenCapture.DisplayCaptureArgs p0) {
        return null;
    }
    public static com.oplus.screenshot.OplusScreenCapture getInstance() {
        return new com.oplus.screenshot.OplusScreenCapture();
    }
    public static class CaptureArgs {
        public static class Builder {
        }
    }
    public static class DisplayCaptureArgs {
        public DisplayCaptureArgs() {
        }
        public static class Builder extends com.oplus.screenshot.OplusScreenCapture.CaptureArgs.Builder {
            public Builder(int p0) {
            }
            public Builder() {
            }
            public com.oplus.screenshot.OplusScreenCapture.DisplayCaptureArgs build() {
                return new com.oplus.screenshot.OplusScreenCapture.DisplayCaptureArgs();
            }
            public com.oplus.screenshot.OplusScreenCapture.CaptureArgs.Builder setExcludeLayers(android.view.SurfaceControl[] p0) {
                return this;
            }
            public com.oplus.screenshot.OplusScreenCapture.DisplayCaptureArgs.Builder setSize(int p0, int p1) {
                return this;
            }
            public com.oplus.screenshot.OplusScreenCapture.CaptureArgs.Builder setSourceCrop(android.graphics.Rect p0) {
                return this;
            }
        }
    }
    public static class ScreenshotHardwareBuffer {
        public android.graphics.Bitmap asBitmap() {
            return null;
        }
        public boolean containsSecureLayers() {
            return false;
        }
    }
}
