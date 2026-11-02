package com.oplus.media;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusHeifConverter {
    public OplusHeifConverter() {
    }
    public static boolean convertHeifToJpegFromStream(java.io.InputStream p0, int p1, java.io.OutputStream p2) {
        return false;
    }
    public boolean createDecoder() {
        return false;
    }
    public com.oplus.media.OplusHeifConverter.HeifDecodedFrame decode(java.io.InputStream p0, int p1, boolean p2) {
        return null;
    }
    public boolean destroyDecoder() {
        return false;
    }
    public int getFormat(java.io.FileDescriptor p0) {
        return 0;
    }
    public int getFormat(java.io.InputStream p0) {
        return 0;
    }
    public static class HeifDecodedFrame {
        public android.graphics.ColorSpace m_ColorSpace;
        public long m_buffer_id;
        public int m_frame_height;
        public int m_frame_width;
        public byte[] m_yuvdata;
        public boolean isRecycled() {
            return false;
        }
        public void recycle() {
        }
        public boolean render(android.view.Surface p0, boolean p1) {
            return false;
        }
        public boolean render(android.view.Surface p0, boolean p1, android.graphics.ColorSpace p2) {
            return false;
        }
    }
}
