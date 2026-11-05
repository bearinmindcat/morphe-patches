package com.nothing;

/**
 * Stand-in for Nothing OS's hidden feature registry. Off a Nothing phone this class does not exist, and Nothing
 * Launcher dies at start when it reflects on it (RuntimeException with the class name). The real one answers which
 * Nothing hardware features are present; off that hardware the honest answer is "none": the launcher finds none of the
 * feature fields it asks for, and isSupport returns false.
 */
public final class NtFeaturesUtils {
    public static boolean isSupport(int[] features) {
        return false;
    }

    private NtFeaturesUtils() {
    }
}
