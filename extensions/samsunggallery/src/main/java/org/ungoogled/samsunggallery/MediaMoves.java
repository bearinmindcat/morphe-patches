package org.ungoogled.samsunggallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Move to album, album rename and the details screen's rename change other apps' photos and videos (their folder or
 * name in MediaStore), which Android allows a gallery that is not the phone's own only with the user's write access.
 * Gallery calls this as each operation starts, on its own thread, with the operation's items: one write request
 * covers all of them (no dialog when the user allowed media management), so Gallery's own steps then succeed. Copies
 * need nothing: they make new files. A move to another storage is a copy, then the original's removal (removeSource).
 * Android keeps pictures and videos under DCIM, Pictures and Movies: a move, copy or album rename into any other
 * folder at the top of a storage first asks for All files access, which lifts that.
 */
public final class MediaMoves {
    /** A storage's root: internal storage (/storage/emulated/0) or an SD card or USB drive (/storage/1234-ABCD). */
    private static final Pattern STORAGE_ROOT = Pattern.compile("/storage/(emulated/\\d+|self/primary|[^/]+)/");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaMoves() {
    }

    /** FileOperationService.onStartInternal: the operation ("copy", "move", "rename") and its queued ProgressJobs. */
    public static void beforeStart(String operation, Collection<?> jobs) {
        boolean move = "move".equals(operation) || "rename".equals(operation);
        if (!move && !"copy".equals(operation)) return;
        Context context = MediaMirror.context();
        if (context == null || jobs == null) return;
        List<Object> items = new ArrayList<>();
        synchronized (jobs) {
            for (Object job : jobs) {
                Object info = param(job);
                if (info != null) items.add(info);
            }
        }
        List<Object> media = new ArrayList<>();
        boolean outside = false;
        for (Object info : items) {
            Object item = GalleryItems.call(info, "getMediaItem");
            if (item == null) continue;
            media.add(item);
            Object dest = GalleryItems.call(info, "getDestPath");
            outside |= dest instanceof String && outsideMediaFolders((String) dest, GalleryItems.video(item));
        }
        if (outside && !MediaRequests.allFilesAccess(context)) {
            Log.i(MediaRequests.TAG, operation + " outside DCIM/Pictures/Movies without all files access");
            MAIN.post(() -> Toast.makeText(context, "Folders at the top of a storage need all files access",
                    Toast.LENGTH_LONG).show());
        }
        if (move) requestWrite(context, media, operation);
    }

    /**
     * Whether MediaStore refuses this destination to an app without All files access: pictures may only go under
     * DCIM or Pictures, videos also under Movies (on any storage).
     */
    static boolean outsideMediaFolders(String dest, boolean video) {
        Matcher root = STORAGE_ROOT.matcher(dest);
        if (!root.lookingAt()) return false;
        String rest = dest.substring(root.end());
        int slash = rest.indexOf('/');
        if (slash <= 0) return true; // At the top of the storage itself.
        String top = rest.substring(0, slash);
        return !(top.equalsIgnoreCase("DCIM") || top.equalsIgnoreCase("Pictures") || (video && top.equalsIgnoreCase("Movies")));
    }

    /**
     * FileEditor.handleFile, on Gallery's file thread before the first item: the details screen's rename, date and
     * location changes (and the timeline's bulk date and location changes) write other apps' files too.
     */
    public static void beforeEdit(Object[] files) {
        Context context = MediaMirror.context();
        if (context == null || files == null || files.length == 0) return;
        requestWrite(context, Arrays.asList(files), "edit");
    }

    /**
     * MpQLocalDbOperation.updateDatabaseByCopyInsteadOfMove: a move to another storage (or one that replaces an
     * item) copies, then removes the original by its MediaStore files uri, which the write access above does not
     * cover, so the original stayed. It is removed here by its image or video uri, which it does.
     */
    public static void removeSource(Object fileInfo) {
        Context context = MediaMirror.context();
        Object item = fileInfo == null ? null : GalleryItems.call(fileInfo, "getMediaItem");
        if (context == null || item == null || !Boolean.TRUE.equals(GalleryItems.call(item, "isLocal"))) return;
        long id = GalleryItems.id(item);
        if (id <= 0) return;
        try {
            int removed = context.getContentResolver().delete(MediaRequests.uri(id, GalleryItems.video(item)), null, null);
            if (Log.isLoggable(MediaRequests.TAG, Log.DEBUG)) Log.d(MediaRequests.TAG, "moved original " + id + " removed: " + removed);
        } catch (RuntimeException e) {
            Log.w(MediaRequests.TAG, "moved original " + id + " not removed: " + e);
        }
    }

    /**
     * Gallery's renameByUri (move, album rename, details rename): MediaStore update of the item's folder and name. On
     * Android 15 and older MediaStore puts the new path of an "external" uri on internal storage, so an item on a
     * memory card was refused ("Changing volume ... not allowed", after making the folder on internal storage): there
     * such an item is updated on its own volume (Gallery's uri only without write access on it). Android 16 and later
     * keep the item's volume; its own volume is the fallback there.
     */
    public static int update(ContentResolver resolver, Uri uri, ContentValues values, String where, String[] args) {
        Uri own = onOwnVolume(resolver, uri);
        if (own == null) return resolver.update(uri, values, where, args);
        if (Build.VERSION.SDK_INT > 35) {
            try {
                return resolver.update(uri, values, where, args);
            } catch (IllegalArgumentException e) {
                Log.i(MediaRequests.TAG, "update retried on " + own.getPathSegments().get(0) + ": " + e.getMessage());
                return resolver.update(own, values, where, args);
            }
        }
        try {
            return resolver.update(own, values, where, args);
        } catch (SecurityException e) {
            Log.i(MediaRequests.TAG, "no write access on " + own.getPathSegments().get(0) + ": " + e.getMessage());
        }
        return resolver.update(uri, values, where, args);
    }

    /** content://media/external/... as content://media/<the item's volume>/..., or null when that is the same. */
    private static Uri onOwnVolume(ContentResolver resolver, Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (!MediaStore.AUTHORITY.equals(uri.getAuthority()) || segments.isEmpty()
                || !MediaStore.VOLUME_EXTERNAL.equals(segments.get(0))) return null;
        String volume = null;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.VOLUME_NAME}, null, null)) {
            if (c != null && c.moveToFirst()) volume = c.getString(0);
        } catch (RuntimeException e) {
            Log.w(MediaRequests.TAG, "volume query failed: " + e);
        }
        if (volume == null || volume.equals(MediaStore.VOLUME_EXTERNAL) || volume.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY)) return null;
        Uri.Builder own = new Uri.Builder().scheme(uri.getScheme()).authority(uri.getAuthority())
                .encodedQuery(uri.getEncodedQuery()).appendPath(volume);
        for (String segment : segments.subList(1, segments.size())) own.appendPath(segment);
        return own.build();
    }

    /** A ProgressJob's FileInfo (its first parameter). */
    private static Object param(Object job) {
        if (job == null) return null;
        try {
            Method get = job.getClass().getMethod("getParam", int.class);
            return get.invoke(job, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(MediaRequests.TAG, "Gallery job " + job.getClass().getName() + ": " + e);
            return null;
        }
    }

    /** Asks write access for the items this app neither made nor was already given. */
    static void requestWrite(Context context, List<?> media, String what) {
        if (Environment.isExternalStorageManager()) return; // All files access writes every picture and video.
        Map<Long, List<Uri>> wanted = new LinkedHashMap<>();
        for (Object item : media) {
            long id = GalleryItems.id(item);
            if (id <= 0 || wanted.containsKey(id)) continue;
            List<Uri> uris = uris(id, GalleryItems.video(item), GalleryItems.path(item));
            if (!granted(context, uris)) wanted.put(id, uris);
        }
        wanted.keySet().removeAll(own(context, new ArrayList<>(wanted.keySet())));
        if (wanted.isEmpty()) return;
        List<Uri> uris = new ArrayList<>();
        for (List<Uri> each : wanted.values()) uris.addAll(each);
        boolean applied = MediaRequests.apply(context, MediaRequests.WRITE, uris);
        Log.i(MediaRequests.TAG, "write access for " + what + " x" + wanted.size() + ": " + applied);
    }

    /**
     * An item's image or video uri, and for one on a memory card also that on its own volume, which update() falls
     * back to (write access is given per uri).
     */
    private static List<Uri> uris(long id, boolean video, String path) {
        List<Uri> uris = new ArrayList<>(2);
        uris.add(MediaRequests.uri(id, video));
        Matcher root = path == null ? null : STORAGE_ROOT.matcher(path);
        if (root != null && root.lookingAt() && !root.group(1).startsWith("emulated/") && !root.group(1).startsWith("self/")) {
            String volume = root.group(1).toLowerCase(Locale.ROOT);
            uris.add(ContentUris.withAppendedId(video ? MediaStore.Video.Media.getContentUri(volume)
                    : MediaStore.Images.Media.getContentUri(volume), id));
        }
        return uris;
    }

    private static boolean granted(Context context, List<Uri> uris) {
        for (Uri uri : uris) {
            if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Of these ids, the ones this app contributed (it may change those itself). */
    private static Set<Long> own(Context context, List<Long> ids) {
        Set<Long> own = new HashSet<>();
        String self = context.getPackageName();
        for (int from = 0; from < ids.size(); from += 500) {
            List<String> chunk = new ArrayList<>();
            for (int i = from; i < Math.min(ids.size(), from + 500); i++) chunk.add(Long.toString(ids.get(i)));
            Bundle args = new Bundle();
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id IN (" + TextUtils.join(",", chunk) + ")");
            try (Cursor c = context.getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, args, null)) {
                while (c != null && c.moveToNext()) {
                    if (self.equals(c.getString(1))) own.add(c.getLong(0));
                }
            } catch (RuntimeException e) {
                Log.w(MediaRequests.TAG, "owner query failed: " + e);
            }
        }
        return own;
    }
}
