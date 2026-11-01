package com.nothing.utils;

/** Stand-in for Nothing OS's utilities. Off a Nothing phone the NDot system font is absent, so it is unsupported. */
public final class Utils {
    public static boolean isSupportNdot(String name) {
        return false;
    }

    private Utils() {
    }
}
