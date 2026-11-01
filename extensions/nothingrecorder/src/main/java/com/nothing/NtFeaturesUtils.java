package com.nothing;

/**
 * Stand-in for Nothing OS's hidden feature registry. Off a Nothing phone this class does not exist, so Nothing
 * Recorder crashes when it reflects on it (RuntimeException with the class name). The real one answers which Nothing
 * hardware features are present; off that hardware the honest answer is "none", so isSupport returns false.
 */
public final class NtFeaturesUtils {
    // The app reflects this field (it must be an int) and passes it to isSupport.
    public static final int NTF_PRIVACY_ICON_MICPHONE = 0;

    public static boolean isSupport(int[] features) {
        return false;
    }

    private NtFeaturesUtils() {
    }
}
