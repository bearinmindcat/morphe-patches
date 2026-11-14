package org.ungoogled.photos;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Hide AI: the generative tools in the Create tab. */
public final class Tools {
    private Tools() {}

    /**
     * The Create tab's launch actions Photos tells Google's server it can open: the generative ones (Remix, video
     * remix, photo to video, outfits, looks, the Gemini agent) are left out, so the server sends no cards for them
     * and the tab's own tool list has none either.
     */
    public static boolean aiAction(Object action) {
        if (!(action instanceof Enum)) return false;
        switch (((Enum<?>) action).name()) {
            case "LAUNCH_PHOTO_REMIX":
            case "LAUNCH_VIDEO_REMIX":
            case "LAUNCH_PHOTO_TO_VIDEO":
            case "LAUNCH_MY_FITS":
            case "LAUNCH_OUTFIT_CREATION":
            case "LAUNCH_MOODS":
            case "LAUNCH_OSWALD":
                return true;
            default:
                return false;
        }
    }

    /**
     * The Create tab's tool row (its older, built-in list): drops the tools whose title is one of [titles] (string
     * resource ids, comma-separated, filled in by the patch).
     */
    public static List<Object> withoutAi(List<?> tools, String titles) {
        List<Object> kept = new ArrayList<>();
        if (tools == null) return kept;
        String[] parts = titles.split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) ids[i] = Integer.parseInt(parts[i]);
        for (Object tool : tools) {
            if (!hasTitle(tool, ids)) kept.add(tool);
        }
        return kept;
    }

    private static boolean hasTitle(Object tool, int[] ids) {
        if (tool == null) return false;
        for (Field field : tool.getClass().getDeclaredFields()) {
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                int value = field.getInt(tool);
                for (int id : ids) {
                    if (value == id) return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not readable: keep the tool.
            }
        }
        return false;
    }
}
