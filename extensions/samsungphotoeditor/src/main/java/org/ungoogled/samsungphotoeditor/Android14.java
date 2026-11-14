package org.ungoogled.samsungphotoeditor;

import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.os.Build;
import android.view.Display;
import android.view.SurfaceControl;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Android 14 methods Photo Editor 3.4.14.63 (made for Android 13 and up) calls without checking the version: Ultra HDR
 * gainmaps and the display's HDR/SDR ratio. The patch sends those calls here: the real method from Android 14 on, "no
 * HDR" on Android 13 (opening a photo died on Display.isHdrSdrRatioAvailable()). Nothing here names a gainmap class
 * at run time below Android 14.
 */
public final class Android14 {
    private static final boolean HAS_HDR = Build.VERSION.SDK_INT >= 34;

    private Android14() {
    }

    public static boolean hasGainmap(Bitmap bitmap) {
        return HAS_HDR && bitmap.hasGainmap();
    }

    public static Gainmap getGainmap(Bitmap bitmap) {
        return HAS_HDR ? bitmap.getGainmap() : null;
    }

    public static void setGainmap(Bitmap bitmap, Gainmap gainmap) {
        if (HAS_HDR) bitmap.setGainmap(gainmap);
    }

    public static boolean isHdrSdrRatioAvailable(Display display) {
        return HAS_HDR && display.isHdrSdrRatioAvailable();
    }

    public static float getHdrSdrRatio(Display display) {
        return HAS_HDR ? display.getHdrSdrRatio() : 1f;
    }

    public static void registerHdrSdrRatioChangedListener(Display display, Executor executor, Consumer<Display> listener) {
        if (HAS_HDR) display.registerHdrSdrRatioChangedListener(executor, listener);
    }

    public static void unregisterHdrSdrRatioChangedListener(Display display, Consumer<Display> listener) {
        if (HAS_HDR) display.unregisterHdrSdrRatioChangedListener(listener);
    }

    public static SurfaceControl.Transaction setExtendedRangeBrightness(SurfaceControl.Transaction transaction,
                                                                       SurfaceControl surface, float current, float desired) {
        return HAS_HDR ? transaction.setExtendedRangeBrightness(surface, current, desired) : transaction;
    }
}
