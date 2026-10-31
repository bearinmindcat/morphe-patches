package com.samsung.android.sume.core;

/**
 * Stand-in for One UI's EXIF reader (an ExifInterface, as the app uses it), built on Android's own ExifInterface over
 * the bytes it is given (no Samsung code).
 */
public class UniExifInterface extends android.media.ExifInterface {
    private UniExifInterface(java.io.InputStream in) throws java.io.IOException {
        super(in);
    }

    public static com.samsung.android.sume.core.UniExifInterface emptyOf() {
        return from(new byte[0]);
    }

    public static com.samsung.android.sume.core.UniExifInterface of(java.nio.ByteBuffer p0) {
        java.nio.ByteBuffer copy = p0.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return from(bytes);
    }

    private static com.samsung.android.sume.core.UniExifInterface from(byte[] bytes) {
        try {
            return new com.samsung.android.sume.core.UniExifInterface(new java.io.ByteArrayInputStream(bytes));
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
