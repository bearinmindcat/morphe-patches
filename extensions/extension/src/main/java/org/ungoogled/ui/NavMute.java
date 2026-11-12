package org.ungoogled.ui;

import android.content.Context;

/**
 * Navigation's Mute state (Settings > Navigation > Mute state, or the speaker button while navigating): the chimes of
 * ours -- speeding, cameras, Waze alerts -- keep quiet while it says Muted, as Maps' own sounds do (issue #168).
 * "Alerts only" still lets them through: they are alerts.
 */
public final class NavMute {
    private NavMute() {}

    /** Maps' mute controller (MuteControllerImpl), from the patch as Maps makes it. */
    private static volatile Object controller;

    public static void onController(Object c) {
        controller = c;
    }

    /** Rewritten by the patch: the controller's mute level now, by name -- UNMUTED, MINIMAL or MUTED. */
    static String level(Object controller) { return null; }

    static boolean muted() {
        Object c = controller;
        if (c != null) {
            try {
                String level = level(c);
                if (level != null) return "MUTED".equals(level);
            } catch (Throwable t) {
                android.util.Log.w("UA", "navigation mute", t);
            }
        }
        // No controller made yet: Maps' stored setting (Muted is kept as binary_mute_level).
        try {
            Context ctx = Shapes.appContext();
            return ctx != null && ctx.getSharedPreferences("settings_preference", Context.MODE_PRIVATE)
                    .getBoolean("binary_mute_level", false);
        } catch (Throwable t) {
            return false;
        }
    }
}
