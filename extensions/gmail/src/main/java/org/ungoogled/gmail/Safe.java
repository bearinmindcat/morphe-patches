package org.ungoogled.gmail;

/** Small guards for Gmail code that meets values a patch had to change. */
public final class Safe {
    private Safe() {}

    /**
     * In place of String.charAt in Gmail's menu inflater, for menu shortcut keys: microG Gmail rewrites the app's
     * strings, and the one string XML cannot hold -- the Delete shortcut, a backspace -- comes back empty, which
     * charAt(0) turned into a crash on every conversation. An empty one now means no shortcut, as a missing one does.
     */
    public static char charAt(String s, int index) {
        return s != null && index >= 0 && index < s.length() ? s.charAt(index) : (char) 0;
    }
}
