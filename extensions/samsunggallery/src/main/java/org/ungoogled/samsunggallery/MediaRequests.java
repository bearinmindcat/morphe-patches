package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Changes to other apps' photos and videos (favourite, move to and out of Android's trash, delete, write access for
 * moves, renames and edits) as Android allows
 * them to a gallery that is not the phone's own: as MediaStore requests the user confirms in Android's dialog, which
 * Android skips for an app the user allowed to manage media. Samsung's Gallery makes these changes through Samsung's
 * MediaProvider, which off One UI refuses them.
 */
public final class MediaRequests {
    static final String TAG = "UA";
    static final int TRASH = 1;
    static final int RESTORE = 2;
    static final int DELETE = 3;
    static final int FAVORITE = 4;
    static final int UNFAVORITE = 5;
    static final int WRITE = 6;

    private static final String PREFS = "ungoogled_media_requests";
    private static final String EXPLAINED = "manage_media_explained";
    /** Requests carry their items as clip data; keep each one well inside a binder transaction. */
    private static final int CHUNK = 500;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, Request> PENDING = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaRequests() {
    }

    static final class Request {
        final PendingIntent intent;
        final boolean explain;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean applied;

        Request(PendingIntent intent, boolean explain) {
            this.intent = intent;
            this.explain = explain;
        }

        void finish(boolean ok) {
            applied = ok;
            done.countDown();
        }
    }

    static Request pending(int id) {
        return PENDING.get(id);
    }

    static Uri uri(long id, boolean video) {
        return ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    /** Whether Android asks the user to confirm trash, restore and delete for this app. */
    static boolean confirms(Context context) {
        return !MediaStore.canManageMedia(context);
    }

    /**
     * Applies one kind of change to these items (ids with whether each is a video). Waits for the user's answer,
     * except on the main thread, where it only starts the request (Android's dialog cannot show while that thread
     * waits) and answers true. Returns whether Android applied it.
     */
    static boolean apply(Context context, int kind, long[] ids, boolean[] videos) {
        if (ids.length == 0) return true;
        context = context.getApplicationContext();
        boolean ok = true;
        for (int from = 0; from < ids.length; from += CHUNK) {
            List<Uri> uris = new ArrayList<>();
            for (int i = from; i < Math.min(ids.length, from + CHUNK); i++) uris.add(uri(ids[i], videos[i]));
            ok &= request(context, kind, uris);
        }
        return ok;
    }

    private static boolean request(Context context, int kind, List<Uri> uris) {
        ContentResolver resolver = context.getContentResolver();
        PendingIntent intent;
        try {
            switch (kind) {
                case TRASH: intent = MediaStore.createTrashRequest(resolver, uris, true); break;
                case RESTORE: intent = MediaStore.createTrashRequest(resolver, uris, false); break;
                case DELETE: intent = MediaStore.createDeleteRequest(resolver, uris); break;
                case WRITE: intent = MediaStore.createWriteRequest(resolver, uris); break;
                default: intent = MediaStore.createFavoriteRequest(resolver, uris, kind == FAVORITE);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "media request refused: " + e);
            return false;
        }
        // Favourites never ask; the others ask unless the user allowed media management, which is explained once.
        boolean explain = kind != FAVORITE && kind != UNFAVORITE && confirms(context) && !explained(context);
        Request request = new Request(intent, explain);
        int id = IDS.incrementAndGet();
        PENDING.put(id, request);
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        Context app = context;
        Runnable start = () -> {
            Intent open = new Intent(app, MediaRequestActivity.class).putExtra(MediaRequestActivity.EXTRA_ID, id);
            Activity front = ActivityState.front();
            try {
                if (front != null) {
                    front.startActivity(open);
                } else {
                    app.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "media request not shown: " + e);
                request.finish(false);
            }
        };
        if (main) {
            start.run();
            return true;
        }
        MAIN.post(start);
        try {
            if (!request.done.await(10, TimeUnit.MINUTES)) Log.w(TAG, "media request unanswered");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            PENDING.remove(id);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "media request " + kind + " x" + uris.size() + ": " + request.applied);
        return request.applied;
    }

    static void forget(int id) {
        PENDING.remove(id);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean explained(Context context) {
        return prefs(context).getBoolean(EXPLAINED, false);
    }

    static void setExplained(Context context) {
        prefs(context).edit().putBoolean(EXPLAINED, true).apply();
    }

    /**
     * Per item, whether it is now trashed (1), in place (0) or gone (absent): what a request did, read back, since
     * Android's answer covers the whole request.
     */
    static Map<Long, Integer> states(Context context, long[] ids) {
        Map<Long, Integer> states = new HashMap<>();
        if (ids.length == 0) return states;
        for (int from = 0; from < ids.length; from += CHUNK) {
            List<String> chunk = new ArrayList<>();
            for (int i = from; i < Math.min(ids.length, from + CHUNK); i++) chunk.add(Long.toString(ids[i]));
            Bundle args = new Bundle();
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id IN (" + TextUtils.join(",", chunk) + ")");
            args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            try (Cursor c = context.getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED}, args, null)) {
                while (c != null && c.moveToNext()) states.put(c.getLong(0), c.getInt(1));
            } catch (RuntimeException e) {
                Log.w(TAG, "media state query failed: " + e);
            }
        }
        return states;
    }
}
