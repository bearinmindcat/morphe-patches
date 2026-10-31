package org.ungoogled.samsungphotoeditor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * One UI's Quram image codec (SemQrBitmapFactory), which Photo Editor decodes and saves photos with, on Android's own
 * BitmapFactory and Bitmap.compress (no Samsung code). The app only carries the codec's SDK stubs; the patch points
 * them here. Options are the app's SemQrBitmapFactory.Options, read and written by field name. The codec's own
 * extras (thumbnail and region modes, YUV buffers, DNG data, ICC profiles) answer "not available".
 */
public final class QuramCodec {
    private static final String TAG = "UA";

    private QuramCodec() {
    }

    // ---- options -------------------------------------------------------------------------------------------------

    private static Object read(Object options, String name) {
        try {
            Field field = options.getClass().getField(name);
            return field.get(options);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void write(Object options, String name, Object value) {
        try {
            options.getClass().getField(name).set(options, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not in this build's options.
        }
    }

    private static BitmapFactory.Options android(Object options) {
        BitmapFactory.Options out = new BitmapFactory.Options();
        if (options == null) return out;
        Object bounds = read(options, "inJustDecodeBounds");
        out.inJustDecodeBounds = bounds instanceof Boolean && (Boolean) bounds;
        Object sample = read(options, "inSampleSize");
        if (sample instanceof Integer && (Integer) sample > 1) out.inSampleSize = (Integer) sample;
        Object config = read(options, "inPreferredConfig");
        if (config instanceof Integer && (Integer) config == 0) out.inPreferredConfig = Bitmap.Config.RGB_565;
        out.inMutable = true;
        return out;
    }

    private static Bitmap answer(Object options, BitmapFactory.Options decoded, Bitmap bitmap) {
        if (options != null) {
            write(options, "outWidth", decoded.outWidth);
            write(options, "outHeight", decoded.outHeight);
            write(options, "width", decoded.outWidth);
            write(options, "height", decoded.outHeight);
            write(options, "outMimeType", decoded.outMimeType);
        }
        return bitmap;
    }

    /** Samsung's sized decodes: the smallest power-of-two reduction still at least width x height. */
    private static void sample(BitmapFactory.Options options, int width, int height) {
        if (width <= 0 || height <= 0 || options.outWidth <= 0 || options.outHeight <= 0) return;
        int sample = 1;
        while (options.outWidth / (sample * 2) >= width && options.outHeight / (sample * 2) >= height) sample *= 2;
        options.inSampleSize = Math.max(options.inSampleSize, sample);
    }

    // ---- decoding ------------------------------------------------------------------------------------------------

    public static Bitmap decodeFile(String path, Object options) {
        BitmapFactory.Options decode = android(options);
        return answer(options, decode, BitmapFactory.decodeFile(path, decode));
    }

    public static Bitmap decodeFile(String path, Object options, int width, int height, int mode) {
        BitmapFactory.Options decode = android(options);
        if (!decode.inJustDecodeBounds) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            decode.outWidth = bounds.outWidth;
            decode.outHeight = bounds.outHeight;
            sample(decode, width, height);
        }
        return answer(options, decode, BitmapFactory.decodeFile(path, decode));
    }

    public static Bitmap decodeStream(InputStream in, Object options) {
        BitmapFactory.Options decode = android(options);
        return answer(options, decode, BitmapFactory.decodeStream(in, null, decode));
    }

    public static Bitmap decodeStream(InputStream in, Rect padding, Object options) {
        BitmapFactory.Options decode = android(options);
        return answer(options, decode, BitmapFactory.decodeStream(in, padding, decode));
    }

    /** A stream can be read once: sized by sample size only when the caller already set one. */
    public static Bitmap decodeStream(InputStream in, int width, int height, Object options) {
        BitmapFactory.Options decode = android(options);
        return answer(options, decode, BitmapFactory.decodeStream(in, null, decode));
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Object options) {
        BitmapFactory.Options decode = android(options);
        return answer(options, decode, BitmapFactory.decodeByteArray(data, offset, length, decode));
    }

    // ---- saving --------------------------------------------------------------------------------------------------

    /** Quality is Samsung's 0-100 JPEG quality; anything outside that saves at 95. Returns 0 on success. */
    private static int save(Bitmap bitmap, String path, int quality) {
        if (bitmap == null || path == null) return -1;
        try (FileOutputStream out = new FileOutputStream(path)) {
            boolean png = path.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
            int q = quality >= 0 && quality <= 100 ? quality : 95;
            return bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, q, out) ? 0 : -1;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Quram stand-in could not save " + path, e);
            return -1;
        }
    }

    public static int compressToFile(Bitmap bitmap, String format, String path, int quality, Object options) {
        return save(bitmap, path, quality);
    }

    public static int compressToFile(Bitmap bitmap, byte[] exif, String format, String path, int quality, Object options) {
        return save(bitmap, path, quality);
    }

    /** width x height is the saved size; quality the last int. */
    public static int resizeCompressToFile(Bitmap bitmap, String format, String path, int width, int height, int mode,
                                           int quality, Object options) {
        return save(scaled(bitmap, width, height), path, quality);
    }

    public static int resizeCompressToFile(Bitmap bitmap, byte[] exif, String format, String path, int width, int height,
                                           int mode, int quality, Object options) {
        return save(scaled(bitmap, width, height), path, quality);
    }

    private static Bitmap scaled(Bitmap bitmap, int width, int height) {
        if (bitmap == null || width <= 0 || height <= 0) return bitmap;
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }
}
