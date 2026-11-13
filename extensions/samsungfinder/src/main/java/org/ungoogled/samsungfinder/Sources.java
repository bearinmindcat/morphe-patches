package org.ungoogled.samsungfinder;

/**
 * Finder keys each search source by its app's package; the sources this port adds (SettingsSource, FilesSource) all
 * live in Finder itself, so each gets a key of its own ("<package>/settings"), the form Finder uses for its own
 * built-in sources, and its own switch in "Apps to search in".
 */
public final class Sources {
    static final String PREFIX = "com.samsung.android.app.galaxyfinder.ungoogled.";

    /**
     * Sources Finder fills itself from the network with Samsung's own keys (YouTube's and Spotify's web APIs, Melon,
     * Galaxy Store, Branch app search): each query would leave the phone without the user ever having agreed to it.
     */
    private static final String[] ONLINE = {
        "com.google.android.youtube/", "com.spotify.music/", "com.iloen.melon/", "com.sec.android.app.samsungapps",
        "com.samsung.android.app.galaxyfinder/branch",
    };

    private Sources() {
    }

    public static String key(String packageName, String authority) {
        if (authority != null && authority.startsWith(PREFIX)) {
            return packageName + "/" + authority.substring(PREFIX.length());
        }
        return packageName;
    }

    /** Removes the online sources (see ONLINE) from Finder's list of sources. */
    public static void dropOnline(java.util.List<?> sources) {
        if (sources == null) return;
        sources.removeIf(source -> {
            try {
                Object key = source.getClass().getMethod("getInformantKey").invoke(source);
                if (!(key instanceof String)) return false;
                for (String online : ONLINE) {
                    if (((String) key).startsWith(online)) return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Kept.
            }
            return false;
        });
    }

    /**
     * The key Finder's policy (order, and whether a source answers as you type or only on "Show more") is looked up
     * by: One UI's Settings and Contacts answer as you type, right after apps; the Settings source here takes One UI
     * Settings' place, Files the next one (One UI's quick panel), and a contacts app's own provider One UI Contacts'.
     */
    public static String policy(String key) {
        if (key == null) return null;
        switch (key) {
            case "com.samsung.android.app.galaxyfinder/settings":
                return "com.android.settings.intelligence";
            case "com.samsung.android.app.galaxyfinder/files":
                return "com.android.systemui";
            case "com.google.android.contacts":
            case "com.android.contacts":
                return "com.samsung.android.app.contacts";
        }
        return key;
    }
}
