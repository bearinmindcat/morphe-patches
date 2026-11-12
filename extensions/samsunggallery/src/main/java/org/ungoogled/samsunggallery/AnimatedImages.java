package org.ungoogled.samsunggallery;

import android.os.Build;
import android.util.Log;

/**
 * Gallery plays GIFs with Samsung's decoder (Quramsoft's libagifencoder.quram.so from One UI's system, or One UI 8's
 * SemAgifDecoder) unless it runs on a "generic device", where it uses Android's own AnimatedImageDrawable (its path for
 * animated WebP on every phone). Off One UI Samsung's decoder is missing, so a GIF opened as a single still frame.
 */
public final class AnimatedImages {
    private static final String TAG = "UA";
    private static volatile Boolean samsungDecoder;

    private AnimatedImages() {
    }

    /** Gallery's own "generic device" answer, or true when Samsung's GIF decoder is not on the phone. */
    public static boolean useAndroidDecoder(boolean generic) {
        if (generic) return true;
        Boolean have = samsungDecoder;
        if (have == null) {
            have = samsungDecoderPresent();
            samsungDecoder = have;
            Log.i(TAG, "gif: " + (have ? "Samsung's decoder" : "Android's decoder (Samsung's is not on this phone)"));
        }
        return !have;
    }

    private static boolean samsungDecoderPresent() {
        // Gallery picks its Samsung decoder the same way (AgifMovie.of).
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Class.forName("com.samsung.android.media.SemAgifDecoder");
                return true;
            } catch (Throwable notOneUi) {
                return false;
            }
        }
        try {
            System.loadLibrary("agifencoder.quram");
            return true;
        } catch (Throwable notOneUi) {
            return false;
        }
    }
}
