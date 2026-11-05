package org.ungoogled.samsunggallery.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import java.io.IOException;

/** nomic-embed-vision-v1.5, run by ncnn in libgallerynn.so (native/gallerysearch.cpp), which the patch adds. */
final class Encoder implements AutoCloseable {
    private static final String TAG = "UA";
    static final int SIDE = 224;

    static {
        System.loadLibrary("gallerynn");
    }

    private static native long open(String param, String model, float[] latent, int threads, boolean halfPrecision);

    private static native void close(long handle);

    private static native float[] embed(long handle, Bitmap bitmap);

    private long handle;

    private Encoder(long handle) {
        this.handle = handle;
    }

    /** The model, loaded (about 200 MB of memory until closed); null when it does not load. threads <= 0: big cores. */
    static Encoder open(Context context, int threads) {
        try {
            // The library is 64-bit ARM only: on an x86 device it runs translated, where half precision is slow.
            boolean translated = Build.SUPPORTED_ABIS.length > 0 && Build.SUPPORTED_ABIS[0].startsWith("x86");
            long h = open(SearchModel.file(context, SearchModel.PARAM).getAbsolutePath(),
                    SearchModel.file(context, SearchModel.BIN).getAbsolutePath(), SearchModel.latent(context), threads,
                    !translated);
            return h == 0 ? null : new Encoder(h);
        } catch (IOException | RuntimeException | LinkageError e) {
            Log.w(TAG, "search: the model did not start", e);
            return null;
        }
    }

    /**
     * The photo's embedding (unit length): the centre square, scaled to 224 pixels as CLIP's preprocessing does
     * (shortest side to 224, centre crop), halving first so that bilinear scaling does not skip pixels.
     */
    float[] embed(Bitmap photo) {
        Bitmap b = photo.getConfig() == Bitmap.Config.ARGB_8888 ? photo : photo.copy(Bitmap.Config.ARGB_8888, false);
        if (b == null) return null;
        int side = Math.min(b.getWidth(), b.getHeight());
        b = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
        while (side >= 2 * SIDE) {
            side /= 2;
            b = Bitmap.createScaledBitmap(b, side, side, true);
        }
        if (side != SIDE) b = Bitmap.createScaledBitmap(b, SIDE, SIDE, true);
        if (b.getConfig() != Bitmap.Config.ARGB_8888) b = b.copy(Bitmap.Config.ARGB_8888, false);
        float[] e = embed(handle, b);
        if (e == null) return null;
        double norm = 0;
        for (float v : e) norm += v * v;
        norm = Math.sqrt(norm);
        if (!(norm > 0)) return null;
        for (int i = 0; i < e.length; i++) e[i] /= norm;
        return e;
    }

    @Override
    public synchronized void close() {
        if (handle != 0) close(handle);
        handle = 0;
    }
}
