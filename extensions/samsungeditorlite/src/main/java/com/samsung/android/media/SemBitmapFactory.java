package com.samsung.android.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/**
 * One UI's bitmap decoder (Samsung's codecs behind BitmapFactory's calls): Android's own BitmapFactory decodes the
 * same images. The EXIF block: the image file itself, which Android's ExifInterface reads the EXIF from.
 */
public class SemBitmapFactory {
    public static Bitmap decodeFile(String path, BitmapFactory.Options options) {
        return BitmapFactory.decodeFile(path, options);
    }

    public static Bitmap decodeStream(InputStream stream, BitmapFactory.Options options) {
        return BitmapFactory.decodeStream(stream, null, options);
    }

    public static byte[] getExifDataFile(String path) {
        if (path == null) return null;
        java.io.File file = new java.io.File(path);
        if (!file.isFile() || file.length() > 64L * 1024 * 1024) return null;
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
