package org.ungoogled.samsunggallery;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Process;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Move to album, album rename and the details screen's rename change other apps' photos and videos (their folder or
 * name in MediaStore), which Android allows a gallery that is not the phone's own only with the user's write access.
 * Gallery calls this as each operation starts, on its own thread, with the operation's items: one write request
 * covers all of them (no dialog when the user allowed media management), so Gallery's own steps then succeed. Copies
 * need nothing: they make new files. A move to another storage is a copy, then the original's removal (removeSource).
 */
public final class MediaMoves {
    private MediaMoves() {
    }

    /** FileOperationService.onStartInternal: the operation ("copy", "move", "rename") and its queued ProgressJobs. */
    public static void beforeStart(String operation, Collection<?> jobs) {
        if (!"move".equals(operation) && !"rename".equals(operation)) return;
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
        for (Object info : items) {
            Object item = GalleryItems.call(info, "getMediaItem");
            if (item != null) media.add(item);
        }
        requestWrite(context, media, operation);
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
        Map<Long, Boolean> wanted = new LinkedHashMap<>();
        for (Object item : media) {
            long id = GalleryItems.id(item);
            if (id <= 0 || wanted.containsKey(id)) continue;
            boolean video = GalleryItems.video(item);
            if (!granted(context, id, video)) wanted.put(id, video);
        }
        wanted.keySet().removeAll(own(context, new ArrayList<>(wanted.keySet())));
        if (wanted.isEmpty()) return;
        long[] ids = new long[wanted.size()];
        boolean[] videos = new boolean[ids.length];
        int i = 0;
        for (Map.Entry<Long, Boolean> e : wanted.entrySet()) {
            ids[i] = e.getKey();
            videos[i++] = e.getValue();
        }
        boolean applied = MediaRequests.apply(context, MediaRequests.WRITE, ids, videos);
        Log.i(MediaRequests.TAG, "write access for " + what + " x" + ids.length + ": " + applied);
    }

    private static boolean granted(Context context, long id, boolean video) {
        return context.checkUriPermission(MediaRequests.uri(id, video), Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
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
