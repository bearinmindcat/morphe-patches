package org.ungoogled.samsungkeyboard;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * Typing languages without Samsung's download. Every language's keyboard layout ships in the app; the download (from
 * Samsung's Galaxy Store server, which answers only Galaxy phones) adds the word predictions. Where that download can't
 * complete, the Languages page offers each language as one that is already there: switched on, it types without word
 * suggestions. Chinese and Japanese are left as they are: their keys only become text through the downloaded
 * dictionaries (pinyin, zhuyin, cangjie and kana-kanji conversion).
 */
public final class Languages {
    private static final String PREFS = "org.ungoogled.samsungkeyboard";
    private static final String REFUSED = "galaxyStoreRefused";

    /** Japanese, Chinese (Hong Kong, China, Taiwan), Cantonese and Hokkien. */
    private static final int[] NEED_DICTIONARY = {
            0x00460000, 0x00470010, 0x00470011, 0x00470012, 0x03520000, 0x03520010, 0x03530000, 0x03530012,
    };

    private static Method getId;
    private static Boolean refused;

    private Languages() {
    }

    private static Context app() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static SharedPreferences prefs() {
        Context app = app();
        return app != null ? app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) : null;
    }

    /** Samsung's server refused this phone (resultCode 1004): remembered, so a Galaxy it refuses gets the same choice. */
    static void refusedByServer() {
        if (Boolean.TRUE.equals(refused)) return;
        refused = true;
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(REFUSED, true).apply();
    }

    /** Whether Samsung's server can deliver language downloads to this phone: a Galaxy it has not refused. */
    public static boolean downloadsPossible() {
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)) return false;
        if (refused == null) {
            SharedPreferences prefs = prefs();
            refused = prefs != null && prefs.getBoolean(REFUSED, false);
        }
        return !refused;
    }

    private static int id(Object language) {
        try {
            if (getId == null) getId = language.getClass().getMethod("getId");
            return (Integer) getId.invoke(language);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * The Languages page's "preloaded" answer for a language: true as the keyboard says, and also for every language
     * that types without a dictionary when downloads can't complete, so it gets an on/off switch instead of a download.
     */
    public static boolean preloaded(Object language, boolean preloaded) {
        if (preloaded || language == null || downloadsPossible()) return preloaded;
        int id = id(language);
        if (id == -1) return false;
        for (int needs : NEED_DICTIONARY) {
            if (id == needs) return false;
        }
        return true;
    }
}
