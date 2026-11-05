package org.ungoogled.onepluslauncher;

/**
 * Hidden Android members OnePlus Launcher needs a real answer for where Android refuses them. Everything else answers
 * "absent" (see Members).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        return NONE;
    }

    static Object get(String key) {
        return NONE;
    }

    static Object construct(String key, Object[] args) {
        return NONE;
    }
}
