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
