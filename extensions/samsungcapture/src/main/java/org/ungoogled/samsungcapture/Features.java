package org.ungoogled.samsungcapture;

import com.samsung.android.feature.SemFloatingFeature;

/**
 * One UI's device features (SemFloatingFeature), as Samsung capture asks for them (SemFloatingFeatureWrapper). The
 * screen recorder only runs where the phone says it has one: off One UI nothing says so, and One UI Core phones
 * (Galaxy A0x/A1x) say no, so there the recorder is turned on.
 */
public final class Features {
    private static final String SCREEN_RECORDER = "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_SCREEN_RECORDER";

    private Features() {
    }

    public static boolean getBoolean(String name) {
        if (SCREEN_RECORDER.equals(name)) return true;
        try {
            SemFloatingFeature features = SemFloatingFeature.getInstance();
            return features != null && features.getBoolean(name);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
