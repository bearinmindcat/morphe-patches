package org.ungoogled.samsunggallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Gallery builds its grid thumbnails and the viewer's first frame from a photo's embedded EXIF thumbnail, and the full
 * picture from the photo itself. A camera can store a thumbnail that is not the photo the way it is saved: GrapheneOS
 * Camera saves selfies "as previewed" by setting a mirrored EXIF orientation (2, 4, 5, 7) on an unmirrored photo and
 * thumbnail, and other cameras mirror the photo's pixels but keep a thumbnail of the unmirrored picture. Either way the
 * grid and the opening frame showed the selfie the other way round and the viewer flipped once the photo was decoded.
 * Gallery gets no embedded thumbnail for photos saved mirrored (EXIF orientation), nor for any photo whose thumbnail is
 * mirrored or upside down compared with the photo (checked against a strip of the photo); its own fallback decodes the
 * photo. Cached thumbnails of mirrored photos and of checked JPEGs get keys of their own, so ones made before from such
 * a thumbnail are not used again.
 */
public final class MirroredPhotos {
    private static final String TAG = "UA";
    /** Width the thumbnail and the photo strip are compared at. */
    private static final int W = 64;
    /** Horizontal bands of the picture; the photo is decoded down to the first band that tells the orientations apart. */
    private static final int BANDS = 8;
    /** Mean grey difference a band needs between its orientations to tell them apart. */
    private static final int TELLS = 10;
    /** Below this a band is as good as symmetric. */
    private static final int MIN_TELLS = 4;
    private static volatile Set<String> paths = Collections.emptySet();
    private static volatile Method orientation;
    private static volatile Method thumbnail;
    private static volatile Method thumbnailBytes;
    private static volatile Method hasThumbnail;
    private static volatile Field filename;
    /** path|size|modified -> whether the embedded thumbnail shows the photo the way the photo is saved. */
    private static final LruCache<String, Boolean> MATCHES = new LruCache<>(4096);

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

    /** The file an androidx ExifInterface was read from, or null (read from a stream or a descriptor). */
    private static String path(Object exif) {
        try {
            Field f = filename;
            if (f == null) {
                f = exif.getClass().getDeclaredField("mFilename");
                f.setAccessible(true);
                filename = f;
            }
            return (String) f.get(exif);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether Gallery may use the photo's embedded thumbnail (bytes: the thumbnail, null to read it). */
    private static boolean usable(Object exif, byte[] bytes) {
        if (mirrored(exif)) return false;
        String path = path(exif);
        if (path == null) return true;
        File file = new File(path);
        String key = path + '|' + file.length() + '|' + file.lastModified();
        Boolean known = MATCHES.get(key);
        if (known != null) return known;
        if (bytes == null) bytes = (byte[]) call(exif, "getThumbnailBytes");
        if (bytes == null || bytes.length == 0) return true;
        boolean matches = matches(path, bytes);
        MATCHES.put(key, matches);
        return matches;
    }

    /**
     * Whether the thumbnail shows the photo the way it is stored, not mirrored, upside down or both: the first band of
     * the thumbnail that looks different in those orientations is compared with the same band of the photo, decoded
     * small. Anything that cannot be told (thumbnail of another shape, a symmetric picture, a decoding error) counts as
     * a match, which is what Gallery did before.
     */
    static boolean matches(String path, byte[] bytes) {
        Bitmap thumb = null;
        Bitmap strip = null;
        BitmapRegionDecoder decoder = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int pw = bounds.outWidth, ph = bounds.outHeight;
            BitmapFactory.Options tb = new BitmapFactory.Options();
            tb.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, tb);
            int tw = tb.outWidth, th = tb.outHeight;
            if (pw <= 0 || ph <= 0 || tw < 16 || th < 16) return true;
            if (Math.abs((float) pw / ph - (float) tw / th) > 0.03f * pw / ph) return true;
            int h = Math.max(BANDS * 4, Math.round((float) W * th / tw) / BANDS * BANDS);
            thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (thumb == null) return true;
            int[] t = grey(thumb, W, h);
            int band = h / BANDS;
            // The first band that looks clearly different mirrored, upside down or both (the photo is decoded only down
            // to it), else the band that differs most.
            int pick = -1, best = -1, bestTells = 0;
            for (int b = 0; b < BANDS; b++) {
                int other = BANDS - 1 - b;
                int tells = Math.min(diff(t, b, t, b, band, true, false),
                        Math.min(diff(t, b, t, other, band, false, true), diff(t, b, t, other, band, true, true)));
                if (tells >= TELLS) {
                    pick = b;
                    break;
                }
                if (tells > bestTells) {
                    bestTells = tells;
                    best = b;
                }
            }
            if (pick < 0) {
                // Symmetric enough that a mirrored thumbnail would hardly show.
                if (bestTells < MIN_TELLS) return true;
                pick = best;
            }
            int other = BANDS - 1 - pick;
            decoder = BitmapRegionDecoder.newInstance(path, false);
            if (decoder == null) return true;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, Integer.highestOneBit(pw / (W * 4)));
            strip = decoder.decodeRegion(new Rect(0, ph * pick / BANDS, pw, ph * (pick + 1) / BANDS), o);
            if (strip == null) return true;
            int[] p = grey(strip, W, band);
            int same = diff(p, 0, t, pick, band, false, false);
            int flipped = Math.min(diff(p, 0, t, pick, band, true, false),
                    Math.min(diff(p, 0, t, other, band, false, true), diff(p, 0, t, other, band, true, true)));
            boolean ok = !(flipped * 10 < same * 6 && same >= MIN_TELLS);
            if (!ok) {
                Log.i(TAG, "mirror: the embedded thumbnail of " + new File(path).getName()
                        + " is not the photo the way it is saved (" + same + " vs " + flipped + "), not used");
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "mirror: embedded thumbnail of " + new File(path).getName() + " matches (" + same + " vs "
                        + flipped + ", band " + pick + ")");
            }
            return ok;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "mirror: thumbnail of " + path + " not checked: " + e);
            return true;
        } finally {
            if (thumb != null) thumb.recycle();
            if (strip != null) strip.recycle();
            if (decoder != null) decoder.recycle();
        }
    }

    /** The bitmap scaled to w x h, as grey values. */
    private static int[] grey(Bitmap b, int w, int h) {
        Bitmap s = Bitmap.createScaledBitmap(b.getConfig() == Bitmap.Config.HARDWARE
                ? b.copy(Bitmap.Config.ARGB_8888, false) : b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        if (s != b) s.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
        }
        return px;
    }

    /**
     * Mean grey difference between band a of picture x (bands of the given height, W wide) and band b of picture y,
     * y's band read mirrored and/or upside down.
     */
    private static int diff(int[] x, int a, int[] y, int b, int band, boolean mirror, boolean upsideDown) {
        long sum = 0;
        for (int r = 0; r < band; r++) {
            int xr = (a * band + r) * W;
            int yr = (b * band + (upsideDown ? band - 1 - r : r)) * W;
            for (int c = 0; c < W; c++) sum += Math.abs(x[xr + c] - y[yr + (mirror ? W - 1 - c : c)]);
        }
        return (int) (sum / ((long) band * W));
    }

    /** ExifInterface.getThumbnail(): none when it does not show the photo the way it is saved. */
    public static byte[] thumbnail(Object exif) {
        byte[] bytes = (byte[]) call(exif, "getThumbnail");
        return bytes != null && usable(exif, bytes) ? bytes : null;
    }

    /** ExifInterface.getThumbnailBytes(): none when it does not show the photo the way it is saved. */
    public static byte[] thumbnailBytes(Object exif) {
        byte[] bytes = (byte[]) call(exif, "getThumbnailBytes");
        return bytes != null && usable(exif, bytes) ? bytes : null;
    }

    /** ExifInterface.hasThumbnail(): false when the thumbnail does not show the photo the way it is saved. */
    public static boolean hasThumbnail(Object exif) {
        Object has = call(exif, "hasThumbnail");
        return has instanceof Boolean && (Boolean) has && usable(exif, null);
    }

    /**
     * The key Gallery's thumbnail disk cache stores a photo under (its path, or its path and a crop): a key of its own for
     * a mirrored photo, and for a JPEG, whose cached thumbnails may have been made from an embedded thumbnail that is not
     * the photo the way it is saved.
     */
    public static String cacheKey(String key) {
        if (key == null) return null;
        Set<String> mirrored = paths;
        if (!mirrored.isEmpty()) {
            if (mirrored.contains(key)) return key + "/mirrored";
            int crop = key.lastIndexOf('/');
            if (crop > 0 && mirrored.contains(key.substring(0, crop))) return key + "/mirrored";
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains(".jpg") || lower.contains(".jpeg") ? key + "/checked" : key;
    }
}
