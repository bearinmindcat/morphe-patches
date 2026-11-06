package org.ungoogled.samsunggoodlock;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

/**
 * Good Lock's network requests, before they leave the phone (its HTTP client's engine calls check first). Good Lock asks
 * the user to agree to its terms on the first screen but already fetched its content files and asked the Galaxy Store for
 * its own update (with the phone's model) before that answer: nothing goes out now until the terms are agreed to, and the
 * Galaxy Store update check never goes out off One UI (it would offer Samsung's unpatched build), so Good Lock takes its
 * own "no answer" path (no update; the content files are fetched after "Agree").
 */
public final class Consent {
    private static final String TAG = "UA";
    private static final String UPDATE_CHECK = "/stub/stubUpdateCheck.as";
    private static final byte[] AGREED_KEY = "Agreed_v".getBytes(StandardCharsets.US_ASCII);
    private static volatile boolean agreed;

    private Consent() {
    }

    /** Throws (Good Lock's no-network path) for a request that must not leave the phone yet; [request] is the engine's. */
    public static void check(Object request) throws IOException {
        String url = urlOf(request);
        if (url == null) return;
        if (url.contains(UPDATE_CHECK) && !Store.galaxyStore()) {
            Log.i(TAG, "Good Lock: Galaxy Store update check answered here (no update)");
            throw new IOException("Good Lock: no Galaxy Store update check off One UI");
        }
        if (!agreed()) {
            Log.i(TAG, "Good Lock: not sent before the terms are agreed to: " + url);
            throw new IOException("Good Lock: nothing is sent before you agree to the terms");
        }
    }

    /** The request's URL: its first field that is one. */
    private static String urlOf(Object request) {
        if (request == null) return null;
        for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(request);
                    if (value == null || value instanceof CharSequence && !(value instanceof String)) continue;
                    String text = String.valueOf(value);
                    if (text.startsWith("https://") || text.startsWith("http://")) return text;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Next field.
                }
            }
        }
        return null;
    }

    /**
     * Whether the terms were agreed to: Good Lock keeps the answer in its "terms" preferences data store, a boolean
     * "Agreed_v<version>" (true once agreed).
     */
    static boolean agreed() {
        if (agreed) return true;
        Context context = App.context();
        if (context == null) return false;
        File file = new File(context.getFilesDir(), "datastore/terms.preferences_pb");
        if (!file.isFile() || file.length() > 4096) return false;
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
        } catch (IOException e) {
            return false;
        }
        // Each entry: key (field 1), then value (field 2) = message { boolean (field 1) }: 0x12 0x02 0x08 0x01 is true.
        outer:
        for (int i = 0; i + AGREED_KEY.length <= data.length; i++) {
            for (int j = 0; j < AGREED_KEY.length; j++) if (data[i + j] != AGREED_KEY[j]) continue outer;
            int k = i + AGREED_KEY.length;
            while (k < data.length && data[k] != 0x12) k++;
            if (k + 3 < data.length && data[k + 1] == 0x02 && data[k + 2] == 0x08 && data[k + 3] == 0x01) {
                agreed = true;
                return true;
            }
        }
        return false;
    }
}
