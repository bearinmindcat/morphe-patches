package org.ungoogled.samsunggallery.search;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import org.ungoogled.samsunggallery.MediaMirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the photos show, kept in Gallery's saved database (gallery_saved.db, attached to the media copy as "saved")
 * in the shape of One UI's tables, so that Gallery's own search and Things/Scenery find it: content_tag rows are
 * the copy's "scene" view (Gallery's keys) and part of its "tag_view" (keys and words, searched by keyword).
 * content_index keeps each photo's path, size and date, so a photo whose id changes is found again by its path and
 * a changed photo is looked at again, and its embedding, so new words can be given without running the model.
 */
public final class SearchIndex {
    private static final String TAG = "UA";
    /** One UI's tag types: 4 = a scene, 5 = a sub-scene (here: a search word). */
    private static final int TYPE_KEY = 4;
    private static final int TYPE_WORD = 5;
    private static final int BATCH = 4;
    /** How long what was found in a photo that went away is kept (Android's trash keeps photos 30 days). */
    private static final long KEEP_MISSING_MS = 30L * 24 * 3600 * 1000;
    /** Gallery's keyword search on the tag view (MpTagView.likeWildTagData): a substring match. */
    private static final Pattern TAG_LIKE = Pattern.compile("T\\.tag_data like '%((?:[^'%]|'')+)%'");

    private SearchIndex() {
    }

    /** The tables in the saved database and the views Gallery's SQL reads (scene; tag_view with the user's tags). */
    public static void createTables(SQLiteDatabase d, String saved) {
        d.execSQL("CREATE TABLE IF NOT EXISTS " + saved + ".content_index (_id INTEGER PRIMARY KEY, _data TEXT, "
                + "date_modified INTEGER, _size INTEGER, labels INTEGER, embedding BLOB, missing_since INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS " + saved + ".content_index_data ON content_index (_data)");
        d.execSQL("CREATE TABLE IF NOT EXISTS " + saved + ".content_tag (sec_media_id INTEGER, tag TEXT, "
                + "tag_type INTEGER, parent TEXT, score REAL)");
        d.execSQL("CREATE INDEX IF NOT EXISTS " + saved + ".content_tag_media ON content_tag (sec_media_id)");
        d.execSQL("CREATE TEMP VIEW IF NOT EXISTS scene AS SELECT rowid AS _id, sec_media_id, tag AS scene_name, "
                + "parent AS parent_name, score AS scene_score, NULL AS scene_region_ratio, 0 AS is_subscene FROM "
                + saved + ".content_tag WHERE tag_type = " + TYPE_KEY);
    }

    /** The part of One UI's tag view this adds (columns as the user's tags have them, negative ids). */
    public static String tagViewPart(String saved) {
        return "SELECT -rowid AS rowid, -rowid AS _id, sec_media_id, tag AS tag_data, tag_type FROM " + saved + ".content_tag";
    }

    /**
     * Gallery finds tags by substring ("car" finds "credit card", "scarf"); with a couple of thousand words that would
     * fill every search with near misses. For what Search by content wrote (tag types 4 and 5) the keyword must be a
     * whole word or words of the tag, plural or not; the user's own tags keep Gallery's match.
     */
    public static String wholeWords(String sql) {
        if (!sql.contains("T.tag_data like '%")) return sql;
        Matcher m = TAG_LIKE.matcher(sql);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String keyword = m.group(1).replace('-', ' ').replace('_', ' ').trim().replaceAll("\\s+", " ");
            List<String> forms = new ArrayList<>();
            forms.add(keyword);
            forms.add(keyword + "s");
            forms.add(keyword + "es");
            if (keyword.endsWith("es") && keyword.length() > 3) forms.add(keyword.substring(0, keyword.length() - 2));
            if (keyword.endsWith("s") && keyword.length() > 2) forms.add(keyword.substring(0, keyword.length() - 1));
            StringBuilder words = new StringBuilder();
            for (String f : forms) {
                if (words.length() > 0) words.append(" OR ");
                words.append("(' ' || REPLACE(REPLACE(T.tag_data, '-', ' '), '_', ' ') || ' ') LIKE '% ").append(f).append(" %'");
            }
            String replacement = "(CASE WHEN T.tag_type IN (" + TYPE_KEY + "," + TYPE_WORD + ") THEN (" + words
                    + ") ELSE " + m.group(0) + " END)";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Removes everything (Search by content turned off). */
    static void clear() {
        MediaMirror.withDatabase(d -> {
            d.execSQL("DELETE FROM saved.content_tag");
            d.execSQL("DELETE FROM saved.content_index");
        });
    }

    /** {photos looked at, photos in all}. */
    static int[] progress(Context context) {
        int[] p = new int[2];
        Map<Long, Media> all = media(context);
        p[1] = all == null ? 0 : all.size();
        MediaMirror.withDatabase(d -> {
            try (Cursor c = d.rawQuery("SELECT count(*) FROM saved.content_index WHERE missing_since IS NULL", null)) {
                if (c.moveToFirst()) p[0] = c.getInt(0);
            }
        });
        p[0] = Math.min(p[0], p[1]);
        return p;
    }

    private static final class Media {
        long id;
        String data;
        long modified;
        long size;
        boolean video;
        long taken;
    }

    /**
     * MediaStore's photos and videos (not pending, not in the trash); null when they can't all be read (no access to
     * all photos: then nothing is removed as gone).
     */
    private static Map<Long, Media> media(Context context) {
        String permission = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null;
        Map<Long, Media> all = new HashMap<>();
        String[] projection = {"_id", "_data", "date_modified", "_size", "media_type", "datetaken"};
        try (Cursor c = context.getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection, "media_type IN (1,3)", null, null)) {
            while (c != null && c.moveToNext()) {
                Media m = new Media();
                m.id = c.getLong(0);
                m.data = c.getString(1);
                m.modified = c.getLong(2);
                m.size = c.getLong(3);
                m.video = c.getInt(4) == 3;
                m.taken = c.isNull(5) ? m.modified * 1000 : c.getLong(5);
                all.put(m.id, m);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "search: MediaStore not read: " + e);
            return null;
        }
        return all;
    }

    /** What content_index holds for one photo. */
    private static final class Indexed {
        String data;
        long modified;
        long size;
        long missingSince;

        boolean same(Media m) {
            return modified == m.modified && size == m.size;
        }
    }

    private static Map<Long, Indexed> indexed() {
        Map<Long, Indexed> have = new HashMap<>();
        MediaMirror.withDatabase(d -> {
            try (Cursor c = d.rawQuery("SELECT _id, _data, date_modified, _size, missing_since FROM saved.content_index", null)) {
                while (c.moveToNext()) {
                    Indexed i = new Indexed();
                    i.data = c.getString(1);
                    i.modified = c.getLong(2);
                    i.size = c.getLong(3);
                    i.missingSince = c.isNull(4) ? 0 : c.getLong(4);
                    have.put(c.getLong(0), i);
                }
            }
        });
        return have;
    }

    /**
     * Brings the index in line with MediaStore before anything is looked at. What was found in a photo that went away
     * (deleted, in the trash, or briefly gone while MediaStore rescans) is kept, unseen -- Gallery shows only photos
     * that exist -- for 30 days, then removed with its tags. A photo MediaStore lists under a new id (its database
     * rebuilt; ids can then swap between photos) gets it back, matched by path, date and size. A row whose id now
     * belongs to a changed or another photo is removed (that photo is looked at again).
     */
    private static int reconcile(Map<Long, Media> media, Map<Long, Indexed> have) {
        long now = System.currentTimeMillis();
        Map<String, Long> byPath = new HashMap<>();
        for (Map.Entry<Long, Indexed> e : have.entrySet()) if (e.getValue().data != null) byPath.put(e.getValue().data, e.getKey());
        Map<Long, Long> moves = new HashMap<>();
        Map<Long, String> renamed = new HashMap<>();
        List<Long> back = new ArrayList<>();
        for (Media m : media.values()) {
            Indexed i = have.get(m.id);
            if (i != null && i.same(m)) {
                if (m.data != null && !m.data.equals(i.data)) renamed.put(m.id, m.data);
                if (i.missingSince != 0) back.add(m.id);
                continue;
            }
            Long old = m.data == null ? null : byPath.get(m.data);
            if (old != null && old != m.id && have.get(old).same(m) && !moves.containsKey(old)) {
                moves.put(old, m.id);
            }
        }
        List<Long> drop = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (Map.Entry<Long, Indexed> e : have.entrySet()) {
            if (moves.containsKey(e.getKey())) continue;
            Media m = media.get(e.getKey());
            if (m != null) {
                if (!e.getValue().same(m)) drop.add(e.getKey());
            } else if (e.getValue().missingSince == 0) {
                missing.add(e.getKey());
            } else if (now - e.getValue().missingSince > KEEP_MISSING_MS) {
                drop.add(e.getKey());
            }
        }
        if (moves.isEmpty() && drop.isEmpty() && renamed.isEmpty() && missing.isEmpty() && back.isEmpty()) return 0;
        MediaMirror.withDatabase(d -> {
            d.beginTransaction();
            try {
                // Moving rows first go out of the way (negative ids), so that ids can swap.
                for (Long old : moves.keySet()) setId(d, old, -old);
                for (Long id : drop) {
                    d.execSQL("DELETE FROM saved.content_tag WHERE sec_media_id = " + id);
                    d.execSQL("DELETE FROM saved.content_index WHERE _id = " + id);
                }
                for (Map.Entry<Long, Long> e : moves.entrySet()) setId(d, -e.getKey(), e.getValue());
                for (Long id : missing) d.execSQL("UPDATE saved.content_index SET missing_since = " + now + " WHERE _id = " + id);
                for (Long id : back) d.execSQL("UPDATE saved.content_index SET missing_since = NULL WHERE _id = " + id);
                for (Map.Entry<Long, Long> e : moves.entrySet()) {
                    d.execSQL("UPDATE saved.content_index SET missing_since = NULL WHERE _id = " + e.getValue());
                }
                for (Map.Entry<Long, String> e : renamed.entrySet()) {
                    ContentValues v = new ContentValues();
                    v.put("_data", e.getValue());
                    d.update("saved.content_index", v, "_id = " + e.getKey(), null);
                }
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        });
        for (Long id : drop) have.remove(id);
        Map<Long, Indexed> moved = new HashMap<>();
        for (Map.Entry<Long, Long> e : moves.entrySet()) moved.put(e.getValue(), have.remove(e.getKey()));
        have.putAll(moved);
        if (!moves.isEmpty()) Log.i(TAG, "search: " + moves.size() + " photos found again under a new id");
        if (!missing.isEmpty()) Log.i(TAG, "search: " + missing.size() + " photos gone, kept unseen for 30 days");
        if (!back.isEmpty()) Log.i(TAG, "search: " + back.size() + " photos back");
        if (!drop.isEmpty()) Log.i(TAG, "search: " + drop.size() + " photos changed or gone for good, their tags removed");
        return drop.size();
    }

    private static void setId(SQLiteDatabase d, long from, long to) {
        d.execSQL("UPDATE saved.content_index SET _id = " + to + " WHERE _id = " + from);
        d.execSQL("UPDATE saved.content_tag SET sec_media_id = " + to + " WHERE sec_media_id = " + from);
    }

    /** Photos looked at before the words changed get the new words from their stored embedding. */
    private static void retag(SearchLabels labels) {
        Map<Long, float[]> old = new HashMap<>();
        MediaMirror.withDatabase(d -> {
            try (Cursor c = d.rawQuery("SELECT _id, embedding FROM saved.content_index WHERE embedding IS NOT NULL "
                    + "AND labels != " + labels.version, null)) {
                while (c.moveToNext()) old.put(c.getLong(0), unpack(c.getBlob(1)));
            }
        });
        if (old.isEmpty()) return;
        for (Map.Entry<Long, float[]> e : old.entrySet()) {
            SearchLabels.Tags tags = labels.choose(e.getValue());
            MediaMirror.withDatabase(d -> {
                d.beginTransaction();
                try {
                    writeTags(d, e.getKey(), tags);
                    d.execSQL("UPDATE saved.content_index SET labels = " + labels.version + " WHERE _id = " + e.getKey());
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
            });
        }
        Log.i(TAG, "search: " + old.size() + " photos given the new words");
    }

    /** Result of a run: looked at, still to do. */
    static final class Run {
        int done;
        int left;
        int removed;
    }

    /**
     * Looks at up to limit photos not looked at yet, newest first, until stopped. The model is loaded only when there
     * is something to look at, and released at the end.
     */
    static Run run(Context context, int limit, int threads, BooleanSupplier stopped) throws IOException {
        Run run = new Run();
        SearchLabels labels = SearchLabels.get(context);
        Encoder encoder = null;
        List<Object[]> batch = new ArrayList<>();
        long started = SystemClock.elapsedRealtime();
        try {
            // Photos that arrive during a long run are picked up when it gets to the end of its list (once each).
            Set<Long> seen = new HashSet<>();
            List<Media> todo;
            while (run.done < limit && !stopped.getAsBoolean()) {
                todo = todo(context, labels, run);
                todo.removeIf(m -> seen.contains(m.id));
                if (todo.isEmpty()) break;
                for (Media m : todo) {
                    if (run.done >= limit || stopped.getAsBoolean()) break;
                    seen.add(m.id);
                    if (encoder == null) {
                        long t = SystemClock.elapsedRealtime();
                        encoder = Encoder.open(context, threads);
                        if (encoder == null) return run;
                        Log.i(TAG, "search: model loaded in " + (SystemClock.elapsedRealtime() - t) + " ms");
                    }
                    long t = SystemClock.elapsedRealtime();
                    float[] e = null;
                    Bitmap thumb = thumbnail(context, m);
                    if (thumb != null) e = encoder.embed(thumb);
                    SearchLabels.Tags tags = e == null ? new SearchLabels.Tags() : labels.choose(e);
                    batch.add(new Object[]{m, e, tags});
                    run.done++;
                    run.left--;
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "search: " + m.id + " in " + (SystemClock.elapsedRealtime() - t) + " ms: "
                                + TextUtils.join(", ", tags.keys) + " | " + TextUtils.join(", ", tags.words));
                    }
                    if (batch.size() >= BATCH) {
                        write(batch, labels.version);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    write(batch, labels.version);
                    batch.clear();
                }
            }
        } finally {
            if (!batch.isEmpty()) write(batch, labels.version);
            if (encoder != null) encoder.close();
        }
        if (run.done > 0) {
            Log.i(TAG, "search: " + run.done + " photos in " + (SystemClock.elapsedRealtime() - started) + " ms, "
                    + run.left + " left");
            context.getContentResolver().notifyChange(MediaMirror.tagViewUri(), null);
        }
        return run;
    }

    /** What is left to look at, newest first; tidies away photos that are gone and retags old ones first. */
    private static List<Media> todo(Context context, SearchLabels labels, Run run) {
        List<Media> todo = new ArrayList<>();
        Map<Long, Media> media = media(context);
        if (media == null) return todo;
        Map<Long, Indexed> have = indexed();
        run.removed += reconcile(media, have);
        retag(labels);
        for (Media m : media.values()) {
            Indexed i = have.get(m.id);
            if (i == null || !i.same(m)) todo.add(m);
        }
        todo.sort((a, b) -> Long.compare(b.taken, a.taken));
        run.left = todo.size();
        return todo;
    }

    private static Bitmap thumbnail(Context context, Media m) {
        Uri uri = ContentUris.withAppendedId(m.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, m.id);
        try {
            return context.getContentResolver().loadThumbnail(uri, new Size(512, 512), null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "search: no thumbnail for " + m.id + ": " + e);
            return null;
        }
    }

    private static void write(List<Object[]> batch, int version) {
        MediaMirror.withDatabase(d -> {
            d.beginTransaction();
            try {
                for (Object[] item : batch) {
                    Media m = (Media) item[0];
                    float[] e = (float[]) item[1];
                    writeTags(d, m.id, (SearchLabels.Tags) item[2]);
                    ContentValues v = new ContentValues();
                    v.put("_id", m.id);
                    v.put("_data", m.data);
                    v.put("date_modified", m.modified);
                    v.put("_size", m.size);
                    v.put("labels", version);
                    if (e != null) v.put("embedding", pack(e));
                    else v.putNull("embedding");
                    d.insertWithOnConflict("saved.content_index", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
                d.setTransactionSuccessful();
            } catch (SQLiteException ex) {
                Log.w(TAG, "search: not saved: " + ex.getMessage());
            } finally {
                d.endTransaction();
            }
        });
    }

    private static void writeTags(SQLiteDatabase d, long id, SearchLabels.Tags tags) {
        d.execSQL("DELETE FROM saved.content_tag WHERE sec_media_id = " + id);
        Set<String> groups = new HashSet<>();
        float best = 0;
        for (int i = 0; i < tags.keys.size(); i++) {
            String parent = tags.parents.get(i);
            float score = tags.scores.get(i);
            insert(d, id, tags.keys.get(i), TYPE_KEY, parent, score);
            if (parent != null) groups.add(parent);
            best = Math.max(best, score);
        }
        // One UI's group rows: a scenery photo is also "Scenery", a document also "Documents" (parent of none).
        if (groups.contains(SearchLabels.SCENERY)) insert(d, id, "Scenery", TYPE_KEY, null, best);
        if (groups.contains(SearchLabels.DOCUMENTS)) insert(d, id, "Documents", TYPE_KEY, null, best);
        for (String w : tags.words) insert(d, id, w, TYPE_WORD, null, 0);
    }

    private static void insert(SQLiteDatabase d, long id, String tag, int type, String parent, float score) {
        ContentValues v = new ContentValues();
        v.put("sec_media_id", id);
        v.put("tag", tag);
        v.put("tag_type", type);
        v.put("parent", parent);
        v.put("score", score);
        d.insert("saved.content_tag", null, v);
    }

    /** 768 numbers as 8 bits each plus one scale (they are only used to choose words again). */
    private static byte[] pack(float[] e) {
        float max = 0;
        for (float v : e) max = Math.max(max, Math.abs(v));
        float scale = max > 0 ? max / 127f : 1f;
        ByteBuffer b = ByteBuffer.allocate(4 + e.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(scale);
        for (float v : e) b.put((byte) Math.round(v / scale));
        return b.array();
    }

    private static float[] unpack(byte[] blob) {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float scale = b.getFloat();
        float[] e = new float[blob.length - 4];
        for (int i = 0; i < e.length; i++) e[i] = b.get() * scale;
        return e;
    }
}
