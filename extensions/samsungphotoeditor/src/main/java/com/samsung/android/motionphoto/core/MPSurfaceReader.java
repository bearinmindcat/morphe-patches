package com.samsung.android.motionphoto.core;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Photo Editor uses, answering "absent" off One UI (no Samsung code). */
public class MPSurfaceReader {
    public MPSurfaceReader() {
    }
    public com.samsung.android.motionphoto.core.MPSurfaceReader.MPSurfaceImage acquireNextImage() {
        return null;
    }
    public void close() {
    }
    public android.view.Surface getSurface() {
        return null;
    }
    public static com.samsung.android.motionphoto.core.MPSurfaceReader of(int p0, int p1, int p2, int p3, long p4) {
        return new com.samsung.android.motionphoto.core.MPSurfaceReader();
    }
    public void setOnImageAvailableListener(com.samsung.android.motionphoto.core.MPSurfaceReader.OnImageAvailableListener p0, android.os.Handler p1) {
    }
    public static class MPSurfaceImage {
        public void close() {
        }
        public int getDataSpace() {
            return 0;
        }
        public int getFormat() {
            return 0;
        }
        public android.hardware.HardwareBuffer getHardwareBuffer() {
            return null;
        }
        public int getHeight() {
            return 0;
        }
        public int getScanline() {
            return 0;
        }
        public int getStride() {
            return 0;
        }
        public long getTimestamp() {
            return 0L;
        }
        public int getWidth() {
            return 0;
        }
    }
    public interface OnImageAvailableListener {
    }
}
