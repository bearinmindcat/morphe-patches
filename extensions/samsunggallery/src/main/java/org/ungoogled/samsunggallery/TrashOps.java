package org.ungoogled.samsunggallery;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Delete, restore and "delete permanently" in Gallery go through Android's trash (see RecycleBin). Gallery's trash
 * helpers handle one item at a time and then call done(); here each item is only queued (counted for the progress
 * bar), and done() makes one MediaStore request for all of them, so Android asks at most once. Each item is then
 * reported to the helper's logger as done or failed, from what MediaStore holds afterwards, as Gallery's own steps
 * did.
 */
public final class TrashOps {
    private static final Map<Object, List<Entry>> QUEUES = Collections.synchronizedMap(new WeakHashMap<>());

    private TrashOps() {
    }

    private static final class Entry {
        final Object item;
        final long id;
        final boolean video;
        final int kind;

        Entry(Object item, long id, boolean video, int kind) {
            this.item = item;
            this.id = id;
            this.video = video;
            this.kind = kind;
        }
    }

    /** TrashDeleteHelper.deleteItemInternal: to the Recycle bin, or deleted when the bin is turned off. */
    public static boolean delete(Object helper, Object item) {
        boolean bin = GalleryItems.flag(helper, "mSupportTrash");
        return queue(helper, item, bin ? MediaRequests.TRASH : MediaRequests.DELETE);
    }

    /** TrashRestoreHelper.restoreItemInternal. */
    public static boolean restore(Object helper, Object item) {
        return queue(helper, item, MediaRequests.RESTORE);
    }

    /** TrashEmptyHelper.emptyItemInternal: deleted for good. */
    public static boolean empty(Object helper, Object item) {
        return queue(helper, item, MediaRequests.DELETE);
    }

    /** Answers false (Gallery's own steps run) for an item that is not a MediaStore photo or video. */
    private static boolean queue(Object helper, Object item, int kind) {
        Context context = MediaMirror.context();
        if (context == null || helper == null || item == null) return false;
        long id = GalleryItems.id(item);
        // Gallery's restore and delete-for-good items keep only the path the bin shows them by.
        if (id <= 0) id = RecycleBin.idOf(context, GalleryItems.path(item));
        if (id <= 0) {
            Log.w(MediaRequests.TAG, "trash: item without a MediaStore id: " + item);
            return false;
        }
        boolean video = GalleryItems.video(item);
        GalleryItems.call(helper, "increaseProgress");
        GalleryItems.log(helper, "started", item);
        // Shown in the bin from this (Gallery cannot read another app's trashed file by its path).
        if (kind == MediaRequests.TRASH) RecycleBin.keepPreview(context, id, video);
        synchronized (QUEUES) {
            List<Entry> queue = QUEUES.get(helper);
            if (queue == null) QUEUES.put(helper, queue = new ArrayList<>());
            queue.add(new Entry(item, id, video, kind));
        }
        return true;
    }

    /** The helpers' done(): applies what was queued. */
    public static void flush(Object helper) {
        List<Entry> queue = QUEUES.remove(helper);
        Context context = MediaMirror.context();
        if (queue == null || queue.isEmpty() || context == null) return;
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        for (int kind : new int[]{MediaRequests.TRASH, MediaRequests.RESTORE, MediaRequests.DELETE}) {
            List<Entry> same = new ArrayList<>();
            for (Entry e : queue) if (e.kind == kind) same.add(e);
            if (same.isEmpty()) continue;
            long[] ids = new long[same.size()];
            boolean[] videos = new boolean[same.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = same.get(i).id;
                videos[i] = same.get(i).video;
            }
            boolean applied = MediaRequests.apply(context, kind, ids, videos);
            Map<Long, Integer> states = main ? null : MediaRequests.states(context, ids);
            int done = 0;
            for (Entry e : same) {
                boolean ok;
                if (states == null) {
                    ok = applied;
                } else {
                    Integer state = states.get(e.id);
                    ok = kind == MediaRequests.DELETE ? state == null
                            : state != null && state == (kind == MediaRequests.TRASH ? 1 : 0);
                }
                GalleryItems.log(helper, ok ? "succeed" : "failed", e.item);
                if (ok) done++;
                if (ok ? kind != MediaRequests.TRASH : kind == MediaRequests.TRASH) RecycleBin.dropPreview(context, e.id);
            }
            Log.i(MediaRequests.TAG, "trash: " + name(kind) + " " + done + "/" + same.size());
        }
    }

    private static String name(int kind) {
        switch (kind) {
            case MediaRequests.TRASH: return "to bin";
            case MediaRequests.RESTORE: return "restored";
            default: return "deleted";
        }
    }
}
