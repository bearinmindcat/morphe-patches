package org.ungoogled.samsungscreenrecorder;

/**
 * One UI members Screen Recorder needs a real answer for off One UI. It needs none: everything a phone lacks answers
 * "absent" (see OneUi).
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
}
