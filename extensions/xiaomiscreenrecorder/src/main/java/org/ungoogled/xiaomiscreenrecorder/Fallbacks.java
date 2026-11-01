package org.ungoogled.xiaomiscreenrecorder;

/**
 * HyperOS members Xiaomi Screen Recorder needs a real answer for off HyperOS (beyond Miui's common ones). Everything else a phone
 * lacks answers "absent" (see Miui).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        return NONE;
    }
}
