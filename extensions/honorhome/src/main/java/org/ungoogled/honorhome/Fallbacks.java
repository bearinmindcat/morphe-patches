package org.ungoogled.honorhome;

/**
 * Hidden Android members Honor Home needs a real answer for as an installed app, from Android's own public calls.
 * Everything else that Android refuses answers "absent" (see Framework).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            // Android 14 calls the launcher makes on Android 13 too: the older call that does the same.
            case "Landroid/util/TypedValue;->deriveDimension(IFLandroid/util/DisplayMetrics;)F": {
                float one = android.util.TypedValue.applyDimension((Integer) args[0], 1f, (android.util.DisplayMetrics) args[2]);
                return one == 0f ? 0f : (Float) args[1] / one;
            }
            case "Landroid/text/Layout;->getTextDirectionHeuristic()Landroid/text/TextDirectionHeuristic;":
                return android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR;
        }
        return NONE;
    }

    /** Objects of classes an installed app may not construct, from their public equivalents. */
    static Object construct(String key, Object[] args) {
        return NONE;
    }

    static Object get(String key) {
        return NONE;
    }
}
