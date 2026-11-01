package org.ungoogled.APP;

/**
 * Members APP_NAME needs a real answer for off RedMagic OS (beyond RedMagic's common ones). Everything else the phone
 * refuses or lacks answers "absent" (see RedMagic).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        return NONE;
    }

    /** The value of a ZTE feature switch off RedMagic OS (see RedMagic.feature), or null: the app's default. */
    static String feature(String key) {
        return null;
    }
}
