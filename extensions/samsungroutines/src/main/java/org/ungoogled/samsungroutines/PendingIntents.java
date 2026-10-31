package org.ungoogled.samsungroutines;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every PendingIntent.getService / getBroadcast / getForegroundService in the app comes here and is remembered with
 * how it was made, so EventDelegation can keep a registration across a restart of the app (a PendingIntent itself
 * cannot be stored).
 */
public final class PendingIntents {
    private static final Map<PendingIntent, String> RECIPES = new WeakHashMap<>();

    private PendingIntents() {
    }

    private static PendingIntent remember(PendingIntent pi, String type, int requestCode, Intent intent, int flags) {
        if (pi != null && intent != null) {
            synchronized (RECIPES) {
                RECIPES.put(pi, type + "|" + requestCode + "|" + flags + "|" + intent.toUri(Intent.URI_INTENT_SCHEME));
            }
        }
        return pi;
    }

    public static PendingIntent getService(Context c, int requestCode, Intent intent, int flags) {
        return remember(PendingIntent.getService(c, requestCode, intent, flags), "service", requestCode, intent, flags);
    }

    public static PendingIntent getForegroundService(Context c, int requestCode, Intent intent, int flags) {
        return remember(PendingIntent.getForegroundService(c, requestCode, intent, flags), "fgservice", requestCode, intent, flags);
    }

    public static PendingIntent getBroadcast(Context c, int requestCode, Intent intent, int flags) {
        return remember(PendingIntent.getBroadcast(c, requestCode, intent, flags), "broadcast", requestCode, intent, flags);
    }

    /** How a PendingIntent was made ("type|requestCode|flags|intent URI"), or null if it was not made here. */
    static String recipe(PendingIntent pi) {
        synchronized (RECIPES) {
            return RECIPES.get(pi);
        }
    }

    /** Makes the PendingIntent a recipe describes again. */
    static PendingIntent make(Context c, String recipe) {
        try {
            String[] parts = recipe.split("\\|", 4);
            int requestCode = Integer.parseInt(parts[1]);
            int flags = Integer.parseInt(parts[2]);
            Intent intent = Intent.parseUri(parts[3], Intent.URI_INTENT_SCHEME);
            switch (parts[0]) {
                case "service":
                    return getService(c, requestCode, intent, flags);
                case "fgservice":
                    return getForegroundService(c, requestCode, intent, flags);
                case "broadcast":
                    return getBroadcast(c, requestCode, intent, flags);
            }
        } catch (Exception e) {
            android.util.Log.w("UA", "cannot remake PendingIntent " + recipe, e);
        }
        return null;
    }
}
