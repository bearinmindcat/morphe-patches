package org.ungoogled.honorweather;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The one question the port asks before any network: Open-Meteo gets the (rounded) position of the phone or the name
 * of a searched city. Asked once: in the app's first-run notice, else (an app that was already set up) on the weather
 * screen. "Don't allow" keeps the app offline: it then shows no
 * weather, as without a connection. The answer is kept in a file of the app's (clearing the app's data asks again),
 * which the app's other processes (widgets, background refresh) read too.
 */
public final class Consent {
    private static final String FILE = "openmeteo_consent";
    private static volatile Boolean allowed;
    private static boolean asking;

    private Consent() {
    }

    private static File file() {
        Context context = Weather.context();
        return context == null ? null : new File(context.getFilesDir(), FILE);
    }

    /** The user's answer, or null before they gave one. */
    private static Boolean answer() {
        File file = file();
        if (file == null || !file.isFile()) return null;
        try {
            return "allow".equals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return null;
        }
    }

    /** True once the user allowed Open-Meteo. */
    static boolean allowed() {
        Boolean known = allowed;
        if (known != null) return known;
        Boolean answer = answer();
        if (answer == null) return false;
        allowed = answer;
        return answer;
    }

    private static boolean toldUnavailable;

    /**
     * A forecast was not fetched because the user did not allow Open-Meteo: says so once per run of the app's screens
     * (else they would only see empty values).
     */
    static void unavailable() {
        if (!Boolean.FALSE.equals(answer())) return;
        synchronized (Consent.class) {
            if (toldUnavailable) return;
            toldUnavailable = true;
        }
        Context context = Weather.context();
        String process = android.app.Application.getProcessName();
        if (context == null || process == null || !process.endsWith(":ui")) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(context,
                "Weather unavailable: Open-Meteo is not allowed.", android.widget.Toast.LENGTH_LONG).show());
    }

    private static boolean decided() {
        return answer() != null;
    }

    /** On the weather screen: asks once, then reloads the screen so the first forecast comes in. */
    public static void ask(Activity activity) {
        if (activity == null || activity.isFinishing() || decided() || !noticeDone(activity)) return;
        synchronized (Consent.class) {
            if (asking) return;
            asking = true;
        }
        new AlertDialog.Builder(activity)
                .setMessage(Weather.noticeText(activity))
                .setCancelable(false)
                .setPositiveButton("Allow", (dialog, which) -> decide(activity, true))
                .setNegativeButton("Don't allow", (dialog, which) -> decide(activity, false))
                .show();
    }

    /** The app's own first-run notice was answered (agreement state 2 or 3, kept where the app keeps it). */
    private static boolean noticeDone(Context context) {
        int state = MagicSettings.secureGetInt(context.getContentResolver(), "weather_secret_notify_remind", -1);
        if (state == -1) state = MagicSettings.systemGetInt(context.getContentResolver(), "weather_secret_notify_remind", 1);
        return state == 2 || state == 3;
    }

    private static void decide(Activity activity, boolean allow) {
        record(allow);
        synchronized (Consent.class) {
            asking = false;
        }
        if (allow && !activity.isFinishing()) activity.recreate();
    }

    /** Keeps the user's answer (the first-run notice records it too). */
    static void record(boolean allow) {
        File file = file();
        if (file != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write((allow ? "allow" : "deny").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // asked again next time
            }
        }
        allowed = allow;
    }
}
