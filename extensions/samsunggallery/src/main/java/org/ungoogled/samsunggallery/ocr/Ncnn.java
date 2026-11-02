package org.ungoogled.samsunggallery.ocr;

import android.graphics.Bitmap;

/** The two PP-OCRv5 networks, run by ncnn in libgalleryocr.so (native/galleryocr.cpp), which the patch adds. */
final class Ncnn {
    private Ncnn() {
    }

    static {
        System.loadLibrary("galleryocr");
    }

    /** Loads both networks; 0 when they did not load. threads <= 0: one per big core. */
    static native long open(String detParam, String detModel, String recParam, String recModel, int threads);

    static native void close(long handle);

    /** Text probability per pixel, row by row, for an ARGB_8888 bitmap with sides that are multiples of 32. */
    static native float[] detect(long handle, Bitmap bitmap);

    /** For each output step (8 pixels of width) of a 48-pixel-high ARGB_8888 line: {class, probability}. */
    static native float[] recognize(long handle, Bitmap line);
}
