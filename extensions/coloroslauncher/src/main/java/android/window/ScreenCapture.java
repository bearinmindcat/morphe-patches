package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class ScreenCapture {
    public static android.window.ScreenCapture.ScreenshotHardwareBuffer captureLayers(android.view.SurfaceControl p0, android.graphics.Rect p1, float p2) {
        return null;
    }
    public static android.window.ScreenCapture.ScreenshotHardwareBuffer captureLayers(android.window.ScreenCapture.LayerCaptureArgs p0) {
        return null;
    }
    public static android.window.ScreenCapture.SynchronousScreenCaptureListener createSyncCaptureListener() {
        return new android.window.ScreenCapture.SynchronousScreenCaptureListener();
    }
    public static class CaptureArgs {
        public CaptureArgs() {
        }
        public static class Builder {
            public Builder() {
            }
            public android.window.ScreenCapture.CaptureArgs build() {
                return new android.window.ScreenCapture.CaptureArgs();
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setExcludeLayers(android.view.SurfaceControl[] p0) {
                return this;
            }
        }
    }
    public static class LayerCaptureArgs {
        public LayerCaptureArgs() {
        }
        public static class Builder {
            public Builder(android.view.SurfaceControl p0) {
            }
            public Builder() {
            }
            public android.window.ScreenCapture.LayerCaptureArgs build() {
                return new android.window.ScreenCapture.LayerCaptureArgs();
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setAllowProtected(boolean p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setCaptureSecureLayers(boolean p0) {
                return null;
            }
            public android.window.ScreenCapture.LayerCaptureArgs.Builder setChildrenOnly(boolean p0) {
                return this;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setExcludeLayers(android.view.SurfaceControl[] p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setFrameScale(float p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setHintForSeamlessTransition(boolean p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setPixelFormat(int p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setSourceCrop(android.graphics.Rect p0) {
                return null;
            }
            public android.window.ScreenCapture.CaptureArgs.Builder setUid(long p0) {
                return null;
            }
        }
    }
    public static class ScreenCaptureListener {
    }
    public static class ScreenshotHardwareBuffer {
        public ScreenshotHardwareBuffer(android.hardware.HardwareBuffer p0, android.graphics.ColorSpace p1, boolean p2, boolean p3) {
        }
        public android.graphics.Bitmap asBitmap() {
            return null;
        }
        public boolean containsSecureLayers() {
            return false;
        }
        public android.graphics.ColorSpace getColorSpace() {
            return null;
        }
        public android.hardware.HardwareBuffer getHardwareBuffer() {
            return null;
        }
    }
    public static class SynchronousScreenCaptureListener {
        public SynchronousScreenCaptureListener() {
        }
    }
}
