package org.ungoogled.APP;

/**
 * LG members APP_NAME needs a real answer for off LG's ROM. Everything else a phone lacks answers "absent" (see Lg).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        return NONE;
    }
}
