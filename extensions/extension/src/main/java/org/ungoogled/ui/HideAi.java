package org.ungoogled.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Hide AI's place sheet half that needs more than a "return null": "Know before you go" as a card of the Overview tab
 * (issue #199). The Overview tab is a server-made list of modules; some of them are just "show the place's content
 * slot N" -- server-built (Elements) cards -- and Know before you go arrives as one of those. Such a slot is left out
 * when it is the slot the place itself names as Know before you go, or when its card is built from a Know before you
 * go template.
 */
public final class HideAi {
    private HideAi() {}

    /** Rewritten by the patch: "placeData;slots;slotConfig;kbygSlot", the names leading from a place to its KBYG slot id. */
    static String kbygPath() { return ""; }

    /** Template names of Know before you go (and Gemini) cards, lower case. */
    private static final String[] MARKERS = {"kbyg", "know_before", "knowbefore", "gemini", "genai", "gen_ai"};

    /**
     * An Overview tab slot module is about to show the place's content slot {@code slot}: the content, or null to leave the
     * module out (what a place without that slot gets).
     */
    public static Object slot(Object content, int slot, Object place) {
        if (content == null || !Shapes.HIDE_AI) return content;
        try {
            String why = slot != 0 && slot == kbygSlot(place) ? "the place's Know before you go slot" : template(content);
            if (why == null) return content;
            android.util.Log.i("UA", "hide ai: overview slot " + slot + " left out (" + why + ")");
            return null;
        } catch (Throwable t) {
            return content;
        }
    }

    private static int kbygSlot(Object place) throws Exception {
        String[] path = kbygPath().split(";");
        if (path.length != 4 || place == null) return 0;
        Method data = find(place.getClass(), path[0]);
        Object o = data.invoke(place);
        for (int i = 1; i < 3 && o != null; i++) o = field(o.getClass(), path[i]).get(o);
        if (o == null) return 0;
        return field(o.getClass(), path[3]).getInt(o);
    }

    /**
     * Why the card is Know before you go, else null: its template (the first "<name>.eml" in its Elements content; the
     * ones after it are the templates it nests, which other cards share) or its English title.
     */
    private static String template(Object content) throws Exception {
        byte[] bytes = (byte[]) content.getClass().getMethod("toByteArray").invoke(content);
        String s = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = s.indexOf(".eml");
        if (at > 0) {
            int start = at;
            while (start > 0 && (Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_')) start--;
            String name = s.substring(start, at).toLowerCase(Locale.ROOT);
            for (String m : MARKERS) if (name.contains(m)) return "template " + name + ".eml";
        }
        return s.contains("Know before you go") ? "\"Know before you go\"" : null;
    }

    private static Method find(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
