package com.oplus.media;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusImageHdrImpl {
    public static boolean compressAlpha8(android.graphics.Bitmap p0, android.graphics.Bitmap.CompressFormat p1, int p2, java.io.OutputStream p3) {
        return false;
    }
    public static android.graphics.Bitmap decodeBaseJpeg(java.io.InputStream p0, android.graphics.BitmapFactory.Options p1) {
        return null;
    }
    public static com.oplus.media.OplusImageHdrImpl.GainmapInfo decodeGainmapAndMetadata(java.io.FileDescriptor p0, int p1) {
        return null;
    }
    public static com.oplus.media.OplusImageHdrImpl.GainmapInfo decodeGainmapAndMetadata(java.io.InputStream p0, int p1) {
        return null;
    }
    public static com.oplus.media.OplusImageHdrImpl.GainmapInfo demuxFile(java.io.FileDescriptor p0) {
        return null;
    }
    public static class GainmapInfo {
        public int mBaseImageType;
        public float mDisplayRatioHdr;
        public float mDisplayRatioSdr;
        public float[] mEpsilonHdr;
        public float[] mEpsilonSdr;
        public android.graphics.Bitmap mGainmap;
        public float[] mGainmapGamma;
        public float[] mGainmapRatioMax;
        public float[] mGainmapRatioMin;
        public float mHdrScale;
        public boolean mIsJpegR;
        public int mType;
    }
}
