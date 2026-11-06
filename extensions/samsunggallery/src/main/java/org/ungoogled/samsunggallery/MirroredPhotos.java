package org.ungoogled.samsunggallery;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
 * Photos saved mirrored (EXIF orientation 2, 4, 5, 7: GrapheneOS Camera's selfies "as previewed") can carry an embedded
 * EXIF thumbnail that is not mirrored the way the photo itself is. Gallery builds its grid thumbnails and the viewer's
 * first frame from that thumbnail and the full picture from the photo, both with the same mirroring, so the grid and the
 * opening frame showed the selfie the other way round and the viewer flipped once the photo was decoded. For these photos
 * Gallery gets no embedded thumbnail (its own fallback decodes the photo), and their cached thumbnails get a key of their
 * own so ones made from the embedded thumbnail before are not used again.
 */
public final class MirroredPhotos {
    private static final String TAG = "UA";
    private static volatile Set<String> paths = Collections.emptySet();
    private static volatile Method orientation;
    private static volatile Method thumbnail;
    private static volatile Method thumbnailBytes;
    private static volatile Method hasThumbnail;

    private MirroredPhotos() {
    }

    /** The paths of the photos the media copy knows as mirrored. */
    static void setPaths(Set<String> mirrored) {
        paths = mirrored;
    }

    static boolean isMirror(int tag) {
        return tag == 2 || tag == 4 || tag == 5 || tag == 7;
    }

    private static Method method(Object exif, String name, Class<?>... parameters) throws NoSuchMethodException {
        return exif.getClass().getMethod(name, parameters);
    }

    /** Whether the photo an androidx ExifInterface was read from is saved mirrored. */
    private static boolean mirrored(Object exif) {
        try {
            Method m = orientation;
            if (m == null) orientation = m = method(exif, "getAttributeInt", String.class, int.class);
            return isMirror((Integer) m.invoke(exif, "Orientation", 1));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "mirror: orientation not read: " + e);
            return false;
        }
    }

    private static Object call(Object exif, String name) {
        try {
            Method m;
            switch (name) {
                case "getThumbnail":
                    m = thumbnail;
                    if (m == null) thumbnail = m = method(exif, name);
                    break;
                case "getThumbnailBytes":
                    m = thumbnailBytes;
                    if (m == null) thumbnailBytes = m = method(exif, name);
                    break;
                default:
                    m = hasThumbnail;
                    if (m == null) hasThumbnail = m = method(exif, name);
            }
            return m.invoke(exif);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "mirror: " + name + " failed: " + e);
            return null;
        }
    }

    /** ExifInterface.getThumbnail(): none for a mirrored photo. */
    public static byte[] thumbnail(Object exif) {
        return mirrored(exif) ? null : (byte[]) call(exif, "getThumbnail");
    }

    /** ExifInterface.getThumbnailBytes(): none for a mirrored photo. */
    public static byte[] thumbnailBytes(Object exif) {
        return mirrored(exif) ? null : (byte[]) call(exif, "getThumbnailBytes");
    }

    /** ExifInterface.hasThumbnail(): false for a mirrored photo. */
    public static boolean hasThumbnail(Object exif) {
        if (mirrored(exif)) return false;
        Object has = call(exif, "hasThumbnail");
        return has instanceof Boolean && (Boolean) has;
    }

    /**
     * The key Gallery's thumbnail disk cache stores a photo under (its path, or its path and a crop): a key of its own for
     * a mirrored photo.
     */
    public static String cacheKey(String key) {
        Set<String> mirrored = paths;
        if (key == null || mirrored.isEmpty()) return key;
        if (mirrored.contains(key)) return key + "/mirrored";
        int crop = key.lastIndexOf('/');
        return crop > 0 && mirrored.contains(key.substring(0, crop)) ? key + "/mirrored" : key;
    }
}
