package org.ungoogled.samsungkeyboard;

/** Small answers the SBoard patches give the keyboard. */
public final class SBoard {
    /** Size types whose table is a height: normal, floating and one-handed keyboards. */
    private static final int NORMAL_HEIGHT = 1;
    private static final int FLOATING_HEIGHT = 21;
    private static final int ONE_HANDED_HEIGHT = 31;

    private SBoard() {
    }

    /** The phone's list of toolbar apps to leave out, plus the two third-party sticker services. */
    public static String withoutThirdPartyStickers(String list) {
        return (list == null ? "" : list) + ",bitmoji,mojitok";
    }

    /** A One UI policy string, or "1" when the phone has none (off One UI). */
    public static String orPresent(String policy) {
        return policy == null || policy.isEmpty() ? "1" : policy;
    }

    /** A size table {smallest, default, largest}: heights get 20% lower and 25% higher limits; the default stays. */
    public static float[] sizeRange(int type, float[] range) {
        if (range == null || range.length != 3) return range;
        if (type != NORMAL_HEIGHT && type != FLOATING_HEIGHT && type != ONE_HANDED_HEIGHT) return range;
        float[] wider = range.clone();
        wider[0] = range[0] * 0.8f;
        wider[2] = range[2] * 1.25f;
        return wider;
    }

    /** The backspace's repeat interval in ms, halved (at least 10 ms). */
    public static int backspaceInterval(int interval) {
        return Math.max(10, interval / 2);
    }

    /** Most toolbar buttons before "...": raised on the presets that show 7 or more (not the dongle's 4). */
    public static int toolbarLimit(int samsung) {
        return samsung >= 7 ? Math.max(samsung, toolbarButtons()) : samsung;
    }

    /** The count chosen in "More toolbar buttons" (the patch makes this return it). */
    public static int toolbarButtons() {
        return 7;
    }
}
