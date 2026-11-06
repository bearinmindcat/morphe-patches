package com.samsung.android.feature;

/**
 * Stand-in (.github/scripts/samsung-standins): names and signatures One Hand Operation + uses, answering "absent" off
 * One UI (no Samsung code). The screen's corner rounding is read as a number, so it answers One UI's usual "4.0"
 * (the app pads its corner swipes by 6x that in dp) instead of an empty string.
 */
public class SemFloatingFeature {
    public SemFloatingFeature() {
    }
    public boolean getBoolean(java.lang.String p0) {
        return false;
    }
    public static com.samsung.android.feature.SemFloatingFeature getInstance() {
        return new com.samsung.android.feature.SemFloatingFeature();
    }
    public int getInt(java.lang.String p0) {
        return 0;
    }
    public java.lang.String getString(java.lang.String p0) {
        if ("SEC_FLOATING_FEATURE_SYSTEMUI_CONFIG_CORNER_ROUND".equals(p0)) return "4.0";
        return "";
    }
}
