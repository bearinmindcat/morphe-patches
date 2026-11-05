package org.ungoogled.ui;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Which Cronet Maps gets on a phone with no Play services at all (issue #69). Maps takes Play services' Cronet;
 * without it, Cronet's own list offers only its plain-Java fallback -- Android's built-in Cronet (HttpEngine,
 * Android 14 and later) joins the list only under a server flag, "score-based provider selection" -- and the
 * map's data channel cannot run on the fallback, so the map stayed empty while search worked. With no real
 * Cronet available that flag reads as on, which puts HttpEngine in the list; with one, nothing changes.
 *
 * A provider that throws while asked counts as unavailable, as it does in Maps' own engine builder (a throwing
 * Play services provider used to leave the flag off, and the fallback in charge). HttpEngine is part of every
 * Android 14+ build, so it is offered there whatever the SDK extension level reads.
 */
public final class CronetChoice {
    private static final String FALLBACK = "Fallback-Cronet-Provider";
    private static final ThreadLocal<Boolean> ASKING = new ThreadLocal<>();
    private static volatile Boolean onlyFallback;

    private CronetChoice() {
    }

    /** True when the only Cronet on offer is the plain-Java fallback. */
    public static boolean onlyFallback(Context context) {
        Boolean known = onlyFallback;
        if (known != null) return known;
        // The list below asks this same question; inside it, answer as Maps would have.
        if (Boolean.TRUE.equals(ASKING.get())) return false;
        ASKING.set(true);
        boolean only = false;
        String found = null;
        try {
            Class<?> provider = Class.forName("org.chromium.net.CronetProvider");
            Method all = provider.getMethod("getAllProviders", Context.class);
            Method name = provider.getMethod("getName");
            Method enabled = provider.getMethod("isEnabled");
            only = true;
            for (Object p : (List<?>) all.invoke(null, context)) {
                try {
                    Object n = name.invoke(p);
                    if (!FALLBACK.equals(n) && Boolean.TRUE.equals(enabled.invoke(p))) {
                        only = false;
                        found = String.valueOf(n);
                        break;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    // Not available; Maps' engine builder skips it the same way.
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            only = false;
            found = "unknown (" + e + ")";
        } finally {
            ASKING.remove();
        }
        onlyFallback = only;
        Log.i("UA", only ? "cronet: no Play services Cronet, using Android's (HttpEngine " + httpEngineVersion() + ")"
                : "cronet: " + found);
        return only;
    }

    /**
     * Android's own Cronet: available on Android 14 and later. Maps also asks for SDK extension 7 there, which every
     * such build has unless its module versions say otherwise; with no other Cronet, take it whenever it exists.
     */
    public static boolean httpEngine(Context context) {
        return Build.VERSION.SDK_INT >= 34 && onlyFallback(context);
    }

    private static String httpEngineVersion() {
        try {
            return (String) Class.forName("android.net.http.HttpEngine").getMethod("getVersionString").invoke(null);
        } catch (Throwable t) {
            return "missing: " + t;
        }
    }
}
