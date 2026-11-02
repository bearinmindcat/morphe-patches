package org.ungoogled.samsunggallery.ocr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.ungoogled.samsunggallery.ActivityState;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Gallery's "Extract text" (the T button on a photo with text) on phones without One UI's text recognizer. Samsung's
 * OCR SDK in Gallery (com.samsung.android.sdk.ocr.Recognizer) asks this class whether an engine is there and, when
 * it creates a recognizer, takes the one made here: PP-OCRv5 run on the phone (TextReader), its results written in
 * Samsung's own result type (SamsungResult), so Samsung's selection, copy and share work unchanged.
 *
 * The models are downloaded once, only after the user allowed it (Models). Until the user decided, the T button is
 * offered on every photo; "Don't allow" hides it.
 */
public final class ExtractText {
    private static final String TAG = "UA";
    private static final String PREFS = "ungoogled_extract_text";
    private static final String CONSENT = "consent";
    private static final String ALLOWED = "allowed";
    private static final String DENIED = "denied";
    /** Longest side the detector works at (PaddleOCR's own default): larger splits big words apart. */
    private static final int READ_SIDE = 960;
    private static final int DETECT_SIDE = 736;

    private static volatile Context app;
    private static final Object ENGINE = new Object();
    private static final Object ASKING = new Object();
    private static TextReader reader;
    private static boolean failed;

    private ExtractText() {
    }

    /** Samsung's Recognizer.isSupported: an engine is there unless the user declined the download. */
    public static boolean isSupported(Context context) {
        remember(context);
        return !DENIED.equals(consent());
    }

    /** Called first in Samsung's Recognizer constructor: the recognizer to use (a com.samsung.android.sdk.ocr.IRecognizer). */
    public static Object recognizer(Context context) {
        remember(context);
        try {
            Class<?> type = Class.forName("com.samsung.android.sdk.ocr.IRecognizer", false, ExtractText.class.getClassLoader());
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new Engine());
        } catch (Throwable t) {
            Log.w(TAG, "extract text: no recognizer", t);
            return null;
        }
    }

    private static void remember(Context context) {
        if (app == null && context != null) app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String consent() {
        return app == null ? null : prefs().getString(CONSENT, null);
    }

    /** IRecognizer: detectText / hasText and recognize are what Samsung's text extraction calls. */
    private static final class Engine implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "detectText":
                case "hasText":
                    return hasText((Bitmap) args[0]);
                case "recognize":
                case "detect":
                    return recognize((Bitmap) args[0], args[1]);
                case "recognizeBlockAt":
                    return recognize((Bitmap) args[0], args[3]);
                case "isPrinted":
                    return true;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "ExtractText";
                default: // cancel, close, isHandwritten, detectBlock
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        }
    }

    static boolean hasText(Bitmap bitmap) {
        if (app == null || bitmap == null || bitmap.isRecycled()) return false;
        String consent = consent();
        if (DENIED.equals(consent)) return false;
        // Before the download the button is how the user gets to say yes.
        if (!Models.ready(app)) return true;
        TextReader r = reader();
        if (r == null) return false;
        int priority = background();
        try {
            long start = SystemClock.elapsedRealtime();
            boolean found = r.hasText(bitmap, DETECT_SIDE);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "extract text: text " + (found ? "found" : "not found") + " in "
                        + (SystemClock.elapsedRealtime() - start) + " ms (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
            }
            return found;
        } catch (Throwable t) {
            Log.w(TAG, "extract text: detection failed", t);
            return false;
        } finally {
            Process.setThreadPriority(priority);
        }
    }

    static boolean recognize(Bitmap bitmap, Object result) {
        if (app == null || bitmap == null || bitmap.isRecycled() || result == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        if (!Models.ready(app)) {
            if (!allowed()) return false;
            if (!Models.download(app)) {
                toast("Couldn't download the text recognizer.");
                return false;
            }
            forgetDetections();
        }
        TextReader r = reader();
        if (r == null) return false;
        int priority = background();
        try {
            long start = SystemClock.elapsedRealtime();
            List<TextReader.Line> lines = r.read(bitmap, READ_SIDE);
            long read = SystemClock.elapsedRealtime() - start;
            if (!lines.isEmpty()) SamsungResult.fill(result, lines);
            Log.i(TAG, "extract text: " + lines.size() + " lines in " + read + " ms (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
            return !lines.isEmpty();
        } catch (Throwable t) {
            Log.w(TAG, "extract text: recognition failed", t);
            return false;
        } finally {
            Process.setThreadPriority(priority);
        }
    }

    /** The networks run below the UI's priority (their worker threads keep the one they start with). */
    private static int background() {
        int priority = Process.getThreadPriority(Process.myTid());
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        return priority;
    }

    private static TextReader reader() {
        synchronized (ENGINE) {
            if (reader != null || failed) return reader;
            try {
                String[] dictionary = new String(Files.readAllBytes(Models.file(app, Models.DICTIONARY).toPath()),
                        StandardCharsets.UTF_8).split("\n");
                long handle = Ncnn.open(path(Models.DET_PARAM), path(Models.DET_MODEL), path(Models.REC_PARAM),
                        path(Models.REC_MODEL), 0);
                if (handle == 0) {
                    failed = true;
                    return null;
                }
                reader = new TextReader(handle, dictionary);
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.w(TAG, "extract text: the recognizer did not start", e);
                failed = true;
            }
            return reader;
        }
    }

    private static String path(String name) {
        return Models.file(app, name).getAbsolutePath();
    }

    /** The user's answer to the one-time download question; asked on the first T. */
    private static boolean allowed() {
        synchronized (ASKING) {
            String consent = consent();
            if (ALLOWED.equals(consent)) return true;
            if (DENIED.equals(consent)) {
                toast("Extract text isn't available.");
                return false;
            }
            Activity activity = ActivityState.front();
            if (activity == null) return false;
            int[] answer = {0};
            CountDownLatch done = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new AlertDialog.Builder(activity)
                            .setTitle("Extract text")
                            .setMessage("Extract text needs a one-time download of a text-recognition model (about "
                                    + Models.MEGABYTES + " MB).")
                            .setPositiveButton("Allow", (d, w) -> answer[0] = 1)
                            .setNegativeButton("Don't allow", (d, w) -> answer[0] = 2)
                            .setOnDismissListener(d -> done.countDown())
                            .show();
                } catch (RuntimeException e) {
                    done.countDown();
                }
            });
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (answer[0] == 1) {
                prefs().edit().putString(CONSENT, ALLOWED).commit();
                return true;
            }
            if (answer[0] == 2) {
                prefs().edit().putString(CONSENT, DENIED).commit();
                forgetDetections();
                toast("Extract text isn't available.");
            }
            return false;
        }
    }

    /**
     * Gallery remembers per photo whether text was found; what it remembered while the recognizer was missing (yes,
     * to offer the download) no longer holds.
     */
    @SuppressWarnings("unchecked")
    private static void forgetDetections() {
        try {
            java.lang.reflect.Field cache = Class.forName("com.samsung.android.gallery.module.clip.textextraction.TextExtractionCache",
                    true, ExtractText.class.getClassLoader()).getDeclaredField("mCache");
            cache.setAccessible(true);
            ((Map<Long, Integer>) cache.get(null)).clear();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    private static void toast(String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }
}
