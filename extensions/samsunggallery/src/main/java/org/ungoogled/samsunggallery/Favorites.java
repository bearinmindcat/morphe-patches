package org.ungoogled.samsunggallery;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gallery's heart. One UI keeps favourites in Samsung's MediaProvider, which Gallery writes as the system app it is
 * there; off One UI that write changes nothing (MediaStore lets an app change only its own files). Favourites go
 * where Android keeps them, MediaStore's IS_FAVORITE, through Android's favourite request (granted without a dialog),
 * so other galleries see them too. Gallery's TagEditor calls these instead of writing the provider.
 */
public final class Favorites {
    private Favorites() {
    }

    /** TagEditor.setFavorite: one item; answers the number of items changed. */
    public static int set(Object item, boolean favorite) {
        return item == null ? 0 : setAll(Collections.singletonList(item), favorite);
    }

    /** TagEditor.addFavoriteInBulk. */
    public static int add(List<?> items) {
        return setAll(items, true);
    }

    /** TagEditor.removeFavoriteInBulk. */
    public static void remove(List<?> items) {
        setAll(items, false);
    }

    static int setAll(List<?> items, boolean favorite) {
        Context context = MediaMirror.context();
        if (context == null || items == null) return 0;
        List<Long> ids = new ArrayList<>();
        List<Boolean> videos = new ArrayList<>();
        for (Object item : items) {
            if (item == null) continue;
            long id = GalleryItems.id(item);
            if (id <= 0) continue;
            ids.add(id);
            videos.add(GalleryItems.video(item));
        }
        if (ids.isEmpty()) return 0;
        long[] idArray = new long[ids.size()];
        boolean[] videoArray = new boolean[ids.size()];
        for (int i = 0; i < idArray.length; i++) {
            idArray[i] = ids.get(i);
            videoArray[i] = videos.get(i);
        }
        // Gallery reloads right after; the copy answers with the new state until MediaStore has it.
        MediaMirror.markFavorite(idArray, favorite);
        boolean applied = MediaRequests.apply(context, favorite ? MediaRequests.FAVORITE : MediaRequests.UNFAVORITE, idArray, videoArray);
        if (Log.isLoggable(MediaRequests.TAG, Log.DEBUG)) {
            Log.d(MediaRequests.TAG, "favourite " + favorite + " x" + idArray.length + ": " + applied);
        }
        return applied ? idArray.length : 0;
    }
}
