package com.oplus.view;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusEdrUtils {
    public static android.view.SurfaceControl getBlastSurfaceControl(android.view.SurfaceView p0) {
        return null;
    }
    public static int getDolbyEdrVersion() {
        return 0;
    }
    public static int getEdrFeatureVersion(int p0) {
        return 0;
    }
    public static int getLocalHdrVersion() {
        return 0;
    }
    public static android.view.SurfaceControl getSurfaceControl(android.view.View p0) {
        return null;
    }
    public static boolean setEdrAuxiliaryImage(android.view.SurfaceControl p0, android.view.SurfaceControl.Transaction p1, android.graphics.Bitmap p2, int p3) {
        return false;
    }
    public static boolean setEdrFlags(android.view.SurfaceControl p0, android.view.SurfaceControl.Transaction p1, int p2) {
        return false;
    }
    public static boolean setEdrImageMetadata(android.view.SurfaceControl p0, android.view.SurfaceControl.Transaction p1, byte[] p2, int p3) {
        return false;
    }
    public static boolean setEdrImageSize(android.view.SurfaceControl p0, android.view.SurfaceControl.Transaction p1, android.util.Size p2, int p3) {
        return false;
    }
    public static boolean setEdrViewTransform(android.view.SurfaceControl p0, android.view.SurfaceControl.Transaction p1, com.oplus.view.OplusEdrUtils.OplusEdrParameters p2, int p3) {
        return false;
    }
    public static class OplusEdrParameters {
        public OplusEdrParameters() {
        }
        public int[] displayPosition;
        public android.graphics.Rect displayRect;
        public float[] displayTransform;
        public int imageRotation;
    }
}
