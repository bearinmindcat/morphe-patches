package org.ungoogled.pixellauncher;

/**
 * Calls into Pixel Launcher's own androidx.preference classes, whose member names differ between builds (R8 renames
 * or drops what the launcher doesn't use). The patch gives every method here a body calling the app's member, found
 * by name or structure; the bodies below only run if the patch found nothing (setOrder: rows stay in added order).
 */
final class Prefs {
    private Prefs() {
    }

    static void setKey(Object preference, String key) {
    }

    static void setTitle(Object preference, CharSequence title) {
    }

    static void setSummary(Object preference, CharSequence summary) {
    }

    static void setOrder(Object preference, int order) {
    }

    /** PreferenceGroup.addPreference. */
    static void add(Object group, Object preference) {
    }

    /** PreferenceGroup.findPreference. */
    static Object find(Object group, CharSequence key) {
        return null;
    }

    /** PreferenceFragmentCompat.getPreferenceScreen. */
    static Object screen(Object fragment) {
        return null;
    }
}
