package org.ungoogled.ui;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        JSONObject importedRecord;
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
            p.id = java.util.UUID.nameUUIDFromBytes(("history:" + key()).getBytes(StandardCharsets.UTF_8)).toString();
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
            if (importedRecord != null) o.put("importedRecord", importedRecord);
            for (int i = 0; i < KINDS.length; i++) if (at[i] > 0) o.put(KIND_NAMES[i], at[i]);
            return o;
        }

        static Entry fromJson(JSONObject o) throws Exception {
            Entry e = new Entry();
            e.ftid = o.has("ftid") ? o.optString("ftid", null) : null;
            e.name = o.optString("name", "");
            e.category = o.optString("category", "");
            SavedStore.readPhotos(o, e.photos);
            e.rating = (float) o.optDouble("rating", Double.NaN);
            if (Float.isInfinite(e.rating)) throw new IOException("Invalid recent-place rating");
            e.reviews = o.optInt("reviews", 0);
            e.lat = o.optDouble("lat");
            e.lng = o.optDouble("lng");
            if (!SavedStore.coordinates(e.lat, e.lng)) throw new IOException("Invalid recent-place coordinates");
            e.importedRecord = o.optJSONObject("importedRecord");
            for (int i = 0; i < KINDS.length; i++) {
                e.at[i] = o.optLong(KIND_NAMES[i], 0);
                e.last = Math.max(e.last, e.at[i]);
            }
            return e;
        }

        static Entry imported(JSONObject o) throws Exception {
            Entry e = fromJson(o);
            e.photos.clear();
            if (e.importedRecord == null) e.importedRecord = new JSONObject(o.toString());
            return e;
        }
    }

    private static boolean loaded;
    private static boolean writeDisabled;
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
        entries.clear();
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists() || new File(f + ".bak").exists()) {
                try (InputStream in = new AtomicFile(f).openRead()) {
                    readArray(new JSONObject(SavedStore.readAll(in)).getJSONArray("places"), false);
                }
            }
            loaded = true;
            writeDisabled = false;
        } catch (Exception e) {
            writeDisabled = true;
            entries.clear();
            android.util.Log.e("UA", "Recent places could not be opened; original file retained", e);
            throw new IllegalStateException("Cannot read recent places. The original file has not been overwritten.", e);
        }
    }

    /** Merges [a] into what is there, keeping each place's latest use of each kind, oldest first. */
    private static void readArray(JSONArray a, boolean imported) throws Exception {
        if (a == null) return;
        LinkedHashMap<String, Entry> all = new LinkedHashMap<>();
        for (Entry e : entries.values()) all.put(e.key(), Entry.fromJson(e.toJson()));
        for (int i = 0; i < a.length(); i++) {
            Entry e = imported ? Entry.imported(a.getJSONObject(i)) : Entry.fromJson(a.getJSONObject(i));
            Entry old = all.get(e.key());
            if (old == null) { all.put(e.key(), e); continue; }
            for (int k = 0; k < KINDS.length; k++) old.at[k] = Math.max(old.at[k], e.at[k]);
            old.last = Math.max(old.last, e.last);
            if ((old.name == null || old.name.isEmpty()) && e.name != null) old.name = e.name;
            if (old.category.isEmpty()) old.category = e.category;
            if (old.photos.isEmpty()) old.photos.addAll(e.photos);
            if (Float.isNaN(old.rating)) old.rating = e.rating;
            if (old.reviews == 0) old.reviews = e.reviews;
            if (old.importedRecord == null) old.importedRecord = e.importedRecord;
        }
        List<Entry> sorted = new ArrayList<>(all.values());
        sorted.sort((x, y) -> Long.compare(x.last, y.last));
        entries.clear();
        for (Entry e : sorted) entries.put(e.key(), e);
        trim();
    }

    static synchronized void save(Context c) {
        try {
            persist(c);
        } catch (Exception e) {
            android.util.Log.e("UA", "Recent places could not be saved", e);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(c, "Recent places could not be saved; original file retained.",
                            android.widget.Toast.LENGTH_LONG).show());
        }
    }

    private static void persist(Context c) throws Exception {
        if (writeDisabled) throw new IOException("Recent places are read-only after a load error");
        byte[] bytes = new JSONObject().put("places", toJson()).toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile file = new AtomicFile(new File(c.getFilesDir(), FILE));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
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
        load(c);
        LinkedHashMap<String, Entry> before = new LinkedHashMap<>(entries);
        try {
            readArray(a, true);
            persist(c);
        } catch (Exception e) {
            entries.clear();
            entries.putAll(before);
            throw e;
        }
    }

    static void validateImport(JSONArray a) throws Exception {
        for (int i = 0; i < a.length(); i++) Entry.imported(a.getJSONObject(i));
    }
}
