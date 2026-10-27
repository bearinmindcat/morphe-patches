package org.ungoogled.ui;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Which Cronet Maps gets on a phone with no Play services at all (issue #69). Maps takes Play services' Cronet;
 * without it, Cronet's own list offers only its plain-Java fallback -- Android's built-in Cronet (HttpEngine,
 * Android 14 and later) joins the list only under a server flag, "score-based provider selection" -- and the
 * map's data channel cannot run on the fallback, so the map stayed empty while search worked. With no real
 * Cronet available that flag reads as on, which puts HttpEngine in the list; with one, nothing changes.
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
        try {
            Class<?> provider = Class.forName("org.chromium.net.CronetProvider");
            Method all = provider.getMethod("getAllProviders", Context.class);
            Method name = provider.getMethod("getName");
            Method enabled = provider.getMethod("isEnabled");
            only = true;
            for (Object p : (List<?>) all.invoke(null, context)) {
                if (!FALLBACK.equals(name.invoke(p)) && Boolean.TRUE.equals(enabled.invoke(p))) {
                    only = false;
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            only = false;
        } finally {
            ASKING.remove();
        }
        onlyFallback = only;
        return only;
    }
}
