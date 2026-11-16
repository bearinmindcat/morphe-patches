package org.ungoogled.ui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * "Your recent places": what Maps keeps as a Google account's Maps history -- the places looked
 * at, routed to, called and shared -- kept here on the phone instead. One JSON file in the app's
 * private storage, at most MAX places, each with the last time it was touched each way. Nothing
 * leaves the phone unless the user exports it, and it can be switched off or cleared.
 */
final class HistoryStore {
    static final String FILE = "ungoogled_history.json";
    /** The kinds of use, as bit flags (what the patch passes). */
    static final int VIEWED = 1, DIRECTIONS = 2, CALLED = 4, SHARED = 8;
    static final int[] KINDS = {VIEWED, DIRECTIONS, CALLED, SHARED};
    static final String[] KIND_NAMES = {"viewed", "directions", "called", "shared"};
    static final String KEY_ON = "history_on";
    private static final int MAX = 500;
    /** Maps binds a place sheet several times: a use repeated within this long is the same one. */
    private static final long SAME_USE = 10 * 60 * 1000L;

    static final class Entry {
        String ftid, name;
        /** What Maps showed of the place, as last seen: category line, photo URLs, rating, review count. */
        String category = "";
        final List<String> photos = new ArrayList<>();
        float rating = Float.NaN;
        int reviews;
        double lat, lng;
        /** The latest use of any kind. */
        long last;
        /** The latest use of each kind, in KINDS order; 0 = never. */
        final long[] at = new long[KINDS.length];

        String key() {
            return ftid != null ? ftid : String.format(Locale.US, "%.6f,%.6f", lat, lng);
        }

        boolean has(int kind) { return at[index(kind)] > 0; }

        /** The kind of the latest use. */
        int lastKind() {
            int best = 0;
            for (int i = 1; i < at.length; i++) if (at[i] > at[best]) best = i;
            return KINDS[best];
        }

        SavedStore.Place asPlace() {
            SavedStore.Place p = new SavedStore.Place();
            p.ftid = ftid; p.name = name; p.lat = lat; p.lng = lng;
            p.category = category;
            p.photos.addAll(photos);
            p.rating = rating;
            p.reviews = reviews;
            return p;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            if (ftid != null) o.put("ftid", ftid);
            o.put("name", name);
            if (!category.isEmpty()) o.put("category", category);
            if (!photos.isEmpty()) o.put("photos", new JSONArray(photos));
            if (!Float.isNaN(rating)) o.put("rating", (double) rating);
            if (reviews > 0) o.put("reviews", reviews);
            o.put("lat", lat);
            o.put("lng", lng);
            for (int i = 0; i < KINDS.length; i++) if (at[i] > 0) o.put(KIND_NAMES[i], at[i]);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.ftid = o.has("ftid") ? o.optString("ftid", null) : null;
            e.name = o.optString("name", "");
            e.category = o.optString("category", "");
            SavedStore.readPhotos(o, e.photos);
            e.rating = (float) o.optDouble("rating", Double.NaN);
            e.reviews = o.optInt("reviews", 0);
            e.lat = o.optDouble("lat");
            e.lng = o.optDouble("lng");
            for (int i = 0; i < KINDS.length; i++) {
                e.at[i] = o.optLong(KIND_NAMES[i], 0);
                e.last = Math.max(e.last, e.at[i]);
            }
            return e;
        }
    }

    private static boolean loaded;
    /** Key -> entry, oldest use first. */
    private static final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    private HistoryStore() {}

    static int index(int kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i] == kind) return i;
        return 0;
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("ungoogled_ui", Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("ungoogled_ui", Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        entries.clear();
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) readArray(new JSONObject(SavedStore.readAll(new FileInputStream(f))).optJSONArray("places"));
        } catch (Throwable ignored) {}
    }

    /** Merges [a] into what is there, keeping each place's latest use of each kind, oldest first. */
    private static void readArray(JSONArray a) throws Exception {
        if (a == null) return;
        LinkedHashMap<String, Entry> all = new LinkedHashMap<>(entries);
        for (int i = 0; i < a.length(); i++) {
            Entry e = Entry.fromJson(a.getJSONObject(i));
            Entry old = all.get(e.key());
            if (old == null) { all.put(e.key(), e); continue; }
            for (int k = 0; k < KINDS.length; k++) old.at[k] = Math.max(old.at[k], e.at[k]);
            old.last = Math.max(old.last, e.last);
            if ((old.name == null || old.name.isEmpty()) && e.name != null) old.name = e.name;
            if (old.category.isEmpty()) old.category = e.category;
            if (old.photos.isEmpty()) old.photos.addAll(e.photos);
            if (Float.isNaN(old.rating)) old.rating = e.rating;
            if (old.reviews == 0) old.reviews = e.reviews;
        }
        List<Entry> sorted = new ArrayList<>(all.values());
        sorted.sort((x, y) -> Long.compare(x.last, y.last));
        entries.clear();
        for (Entry e : sorted) entries.put(e.key(), e);
        trim();
    }

    static synchronized void save(Context c) {
        try {
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("places", toJson()).toString(1).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) tmp.delete();
        } catch (Throwable ignored) {}
    }

    /** A use of [p] of [kind]: moves it to the top of the recent places, unless it is the same use again. */
    static synchronized void record(Context c, SavedStore.Place p, int kind) {
        if (!enabled(c)) return;
        load(c);
        long now = System.currentTimeMillis();
        int i = index(kind);
        Entry e = entries.get(p.key());
        if (e != null && now - e.at[i] < SAME_USE) {
            // The same use again: only keep what Maps now knows about the place.
            if (fill(e, p)) save(c);
            return;
        }
        if (e != null) entries.remove(p.key());
        else e = new Entry();
        e.ftid = p.ftid;
        if (p.name != null && !p.name.isEmpty()) e.name = p.name;
        fill(e, p);
        e.lat = p.lat;
        e.lng = p.lng;
        e.at[i] = now;
        e.last = now;
        entries.put(p.key(), e);
        trim();
        save(c);
    }

    /** Takes what Maps now shows of [e]'s place (category, photos, rating); true if any of it changed. */
    private static boolean fill(Entry e, SavedStore.Place p) {
        boolean changed = false;
        if (p.category != null && !p.category.isEmpty() && !p.category.equals(e.category)) { e.category = p.category; changed = true; }
        if (!p.photos.isEmpty() && !p.photos.equals(e.photos)) { e.photos.clear(); e.photos.addAll(p.photos); changed = true; }
        if (!Float.isNaN(p.rating) && p.rating != e.rating) { e.rating = p.rating; changed = true; }
        if (p.reviews > 0 && p.reviews != e.reviews) { e.reviews = p.reviews; changed = true; }
        return changed;
    }

    private static void trim() {
        Iterator<String> it = entries.keySet().iterator();
        while (entries.size() > MAX && it.hasNext()) { it.next(); it.remove(); }
    }

    /** Every recent place, newest first. */
    static synchronized List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>(entries.values());
        Collections.reverse(out);
        return out;
    }

    static synchronized Entry find(String key) {
        return entries.get(key);
    }

    static synchronized void delete(Context c, String key) {
        if (entries.remove(key) != null) save(c);
    }

    static synchronized void clear(Context c) {
        entries.clear();
        save(c);
    }

    static synchronized int size() {
        return entries.size();
    }

    static synchronized JSONArray toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries.values()) a.put(e.toJson());
        return a;
    }

    /** From an imported backup: merged in, keeping the latest time of each use. */
    static synchronized void merge(Context c, JSONArray a) throws Exception {
        SavedStore.removeImportedPhotos(a);
        load(c);
        readArray(a);
        save(c);
    }
}
