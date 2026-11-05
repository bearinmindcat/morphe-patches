package com.hihonor.android.content;

/** Stand-in (written by hand): MagicOS's per-user content observers, for the app's own user. */
public class ContentResolverEx {
    public static void registerContentObserver(android.content.ContentResolver p0, android.net.Uri p1, boolean p2, android.database.ContentObserver p3, int p4) {
        try {
            p0.registerContentObserver(p1, p2, p3);
        } catch (RuntimeException e) {
            // A settings key an installed app may not watch: no updates, as before.
        }
    }
}
