package org.ungoogled.photos;

/**
 * Photos' feature promos (banners, half sheets, tooltips, cards) each carry a category and a surface; the
 * promo manager asks these before it shows one it picked.
 */
public final class Promos {
    private Promos() {}

    /**
     * Hide promos: offers and nags -- Google One and other paid offers, "try this" launches, "turn on" asks for
     * free features, backup nags and Google's in-app messages. Privacy and legal notices, broken-state warnings
     * (storage full, payment failed), plain tips and memories stay.
     */
    public static boolean upsell(Object category) {
        if (!(category instanceof Enum)) return false;
        switch (((Enum<?>) category).name()) {
            case "MONETIZATION":
            case "GTM":
            case "FREE_FEATURES":
            case "BACKUP":
            case "GNP_IN_APP":
                return true;
            default:
                return false;
        }
    }

    /** Hide AI: promos for Ask Photos, Gemini, Remix, photo-to-video, outfits and the other generative tools. */
    public static boolean ai(String id, Object surface) {
        if (surface instanceof Enum) {
            String name = ((Enum<?>) surface).name();
            if (name.equals("ASK_PHOTOS") || name.equals("MY_FITS")) return true;
        }
        if (id == null) return false;
        String s = id.toLowerCase(java.util.Locale.ROOT);
        for (String word : AI_WORDS) {
            if (s.contains(word)) return true;
        }
        return false;
    }

    private static final String[] AI_WORDS = {
        "ask_photos", "ellmann", "agent_chat", "gemini", "remix", "bluejay", "kefir", "soba", "looks", "mft",
        "my_fits", "outfit", "magic", "udon", "genai", "gen_ai",
    };
}
