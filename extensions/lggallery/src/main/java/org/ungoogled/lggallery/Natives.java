package org.ungoogled.lggallery;

import android.os.Process;
import android.util.Log;

/**
 * LG Gallery's native libraries (Quram's JPEG decoder, the 3D tilt and depth effects, ...) are 32-bit ARM only: a
 * phone that runs the app as a 32-bit process loads them as on LG's phones; a 64-bit-only phone has nothing to load
 * them with. There every call into them answers "nothing" (no bitmap, 0, false) and the app takes the paths it has for
 * a failed decode (Android's own decoders) instead of dying on UnsatisfiedLinkError.
 */
public final class Natives {
    private static final String TAG = "UA";
    private static final boolean AVAILABLE = !Process.is64Bit();

    private Natives() {
    }

    /** True where the app's native libraries can load. */
    public static boolean ok() {
        return AVAILABLE;
    }

    /**
     * Gallery's choice of Android's region decoder (zoomed photos) over Quram's, which it makes for every format but
     * JPEG: JPEGs too where Quram's library cannot load.
     */
    public static boolean androidRegionDecoder(boolean useAndroid) {
        return useAndroid || !AVAILABLE;
    }

    /** System.loadLibrary that never throws: the libraries' classes stay usable where they cannot load. */
    public static void loadLibrary(String name) {
        if (!AVAILABLE) return;
        try {
            System.loadLibrary(name);
        } catch (Throwable t) {
            Log.w(TAG, "LG Gallery: cannot load lib" + name + ": " + t);
        }
    }
}
