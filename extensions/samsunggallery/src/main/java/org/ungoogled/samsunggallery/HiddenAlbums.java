package org.ungoogled.samsunggallery;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Hide albums" (Albums > More) did nothing: Gallery hides an album by setting is_hide (1 hidden, -1 shown) on its files
 * in Samsung's media provider, a column Android's MediaStore does not have. The change goes to the media copy instead,
 * which keeps hidden albums in its saved database; Gallery's own queries (albums list, timeline, stories) already leave
 * out albums whose files have is_hide = 1.
 */
public final class HiddenAlbums {
    private static final String TAG = "UA";
    private static final Pattern BUCKET = Pattern.compile("^\\s*bucket_id\\s*=\\s*(-?\\d+)\\s*$");

    private HiddenAlbums() {
    }

    /** Gallery's update of an album's files (AlbumApi.updateAlbumsDbForSec). */
    public static int update(ContentResolver resolver, Uri uri, ContentValues values, String where, String[] args) {
        Integer hide = values == null ? null : values.getAsInteger("is_hide");
        Matcher bucket = where == null ? null : BUCKET.matcher(where);
        if (hide == null || values.size() != 1 || bucket == null || !bucket.matches()) {
            return resolver.update(uri, values, where, args);
        }
        try {
            return MediaMirror.setAlbumHidden(Long.parseLong(bucket.group(1)), hide > 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "hide album: " + e);
            return 0;
        }
    }
}
