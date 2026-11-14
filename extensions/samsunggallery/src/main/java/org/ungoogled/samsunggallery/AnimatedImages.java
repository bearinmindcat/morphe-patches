package org.ungoogled.samsunggallery;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Gallery plays GIFs with Samsung's decoder (Quramsoft's libagifencoder.quram.so from One UI's system, or One UI 8's
 * SemAgifDecoder) unless it runs on a "generic device", where it uses Android's own AnimatedImageDrawable (its path for
 * animated WebP on every phone). Off One UI Samsung's decoder is missing, so a GIF opened as a single still frame (or,
 * before Android 16, crashed Gallery).
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
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "gif: SemAgifDecoder " + whereIsSemAgifDecoder());
            Log.i(TAG, "gif: " + (have ? "Samsung's decoder" : "Android's decoder (Samsung's is not on this phone)"));
        }
        return !have;
    }

    /** For the debug log: which SemAgifDecoder a plain lookup finds, and whether the framework has one. */
    private static String whereIsSemAgifDecoder() {
        String name = "com.samsung.android.media.SemAgifDecoder";
        String app;
        try {
            Class<?> found = Class.forName(name);
            app = found.getClassLoader() == AnimatedImages.class.getClassLoader() ? "Gallery's stub" : "framework";
        } catch (Throwable e) {
            app = "none";
        }
        String framework;
        try {
            Class.forName(name, false, Context.class.getClassLoader());
            framework = "yes";
        } catch (Throwable e) {
            framework = "no";
        }
        return "lookup finds " + app + ", in the framework: " + framework;
    }

    private static boolean samsungDecoderPresent() {
        // Gallery picks its Samsung decoder the same way (AgifMovie.of).
        if (Build.VERSION.SDK_INT >= 36) {
            // Only One UI's framework has a working SemAgifDecoder. Gallery carries a compile-time copy of the class
            // (every method throws "Stub!"), so a plain lookup always found one: on Android 16 and later every GIF
            // stayed a still frame. Look in the framework only.
            try {
                Class<?> decoder = Class.forName("com.samsung.android.media.SemAgifDecoder", false,
                        Context.class.getClassLoader());
                return decoder.getClassLoader() != AnimatedImages.class.getClassLoader();
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
