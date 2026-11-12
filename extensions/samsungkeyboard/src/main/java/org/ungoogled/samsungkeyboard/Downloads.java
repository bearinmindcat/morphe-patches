package org.ungoogled.samsungkeyboard;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Predictive text models and languages come from Samsung's Galaxy Store server, which answers resultCode 1004 ("deviceId
 * is invalid ... registered at Galaxy Store") for a phone model it does not know, that is every phone but a Galaxy. The
 * keyboard then ended the download with no word to the user (its notification needs a permission and a channel the
 * installed copy has neither of), and unless its Languages page was open it never cleared the download: the language
 * showed "downloading" until the keyboard restarted, and could not be downloaded again. Here a failed download is cleared
 * the way the Languages page clears it, and the user is told why when Samsung refused the phone.
 */
public final class Downloads {
    private static final String MESSAGE = "Samsung only offers predictive text downloads to Galaxy phones.";
    private static final String LANGUAGE = "com.samsung.android.honeyboard.base.languagepack.language.Language";
    /** The keyboard's code for a download that was cancelled (by the user, by no network, or by the clearing below). */
    private static final int CANCELLED = -17;

    /** Whether Samsung's last answer refused this phone. */
    private static volatile boolean refused;
    private static long shown;

    /** The keyboard's list of running downloads and its method that ends one (language, failed). */
    private static volatile Object downloads;
    private static volatile Method end;

    private Downloads() {
    }

    /** Samsung's answer to one of the keyboard's requests (the XML it received, empty when the request failed). */
    public static void answer(String xml) {
        refused = xml != null && xml.contains("<resultCode>1004</resultCode>");
    }

    /** The keyboard's list of running downloads, as it is made, and the name of its method that ends one. */
    public static void downloads(Object list, String name) {
        for (Method m : list.getClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(name) && p.length == 2 && p[0].getName().equals(LANGUAGE) && p[1] == boolean.class) {
                m.setAccessible(true);
                end = m;
                downloads = list;
                return;
            }
        }
    }

    /** A language download (job) ended with the keyboard's error code. */
    public static void failed(Object job, int code) {
        if (code == CANCELLED) return;
        Object language = languageOf(job);
        boolean refusedNow = refused;
        // After the keyboard's own handling (an open Languages page clears it first; ending it again changes nothing).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Object list = downloads;
            Method method = end;
            if (list != null && method != null && language != null) {
                try {
                    method.invoke(list, language, false);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Left as the keyboard left it.
                }
            }
            if (refusedNow) show();
        }, 1000);
    }

    private static Object languageOf(Object job) {
        for (Class<?> c = job.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(LANGUAGE)) continue;
                try {
                    f.setAccessible(true);
                    return f.get(job);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void show() {
        // Several languages at once end together: one message.
        long now = SystemClock.elapsedRealtime();
        if (shown != 0 && now - shown < 4000) return;
        shown = now;
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            Toast.makeText((Context) app, MESSAGE, Toast.LENGTH_LONG).show();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No application yet: nothing to show it with.
        }
    }
}
