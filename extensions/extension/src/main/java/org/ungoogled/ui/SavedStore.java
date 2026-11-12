package org.ungoogled.ui;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Places saved without a Google account, modelled on Maps' own: a place is
 * saved (Save), may sit in any of the lists -- Want to go, Travel plans, Starred
 * places, Favorites and the user's own -- and may carry a note; Home, Work and
 * the user's own labels ("Labeled") sit alongside. One JSON file in the app's
 * private storage; nothing leaves the phone unless the user exports it.
 */
final class SavedStore {
    static final String FILE = "ungoogled_saved.json";
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    static final String WANT_TO_GO = "want_to_go", TRAVEL = "travel_plans", STARRED = "starred", FAVOURITES = "favourites";
    /** The first version's catch-all list; its places are simply saved now. */
    private static final String OLD_SAVED = "saved";
    private static final String[] PLACE_FIELDS = {
            "id", "separateEntry", "ftid", "cid", "placeId", "sourceUrl", "name", "note", "category", "hours",
            "photo", "photos", "thumbnailUrl", "rating", "reviews", "hasCoordinates", "lat", "lng", "sharedLat", "sharedLng",
            "added", "lists", "membershipIds", "listNotes", "listEntries", "sources", "detailsState", "thumbnailState",
            "attempts", "thumbnailAttempts", "retryAt", "metadataAt", "detailError", "thumbnailError",
            "candidateFtid", "candidateName", "importAliases"
    };

    static final class Place {
        String id = UUID.randomUUID().toString();
        boolean separateEntry;
        String ftid;            // Maps' feature id, "0x..:0x..", or null for a bare spot
        String cid, placeId;
        String sourceUrl = "";
        String name;
        String note = "";
        final Map<String, String> listNotes = new LinkedHashMap<>();
        final Map<String, String> membershipIds = new LinkedHashMap<>();
        /** The original, list-scoped source projection, never the native details cache. */
        final Map<String, JSONObject> listEntries = new LinkedHashMap<>();
        final List<JSONObject> sources = new ArrayList<>();
        private JSONObject extra = new JSONObject();
        String detailsState = "none", thumbnailState = "none", detailError = "", thumbnailError = "";
        String candidateFtid = "", candidateName = "";
        final Set<String> importAliases = new LinkedHashSet<>();
        int attempts, thumbnailAttempts;
        long retryAt, metadataAt;
        /** Coordinates supplied by the source, not coordinates fetched from Maps. */
        double sharedLat = Double.NaN, sharedLng = Double.NaN;
        /** What Maps showed of the place, when known: its category line ("Real estate agency"), photo URLs, rating and review count. */
        String category = "";
        String hours = "";
        final List<String> photos = new ArrayList<>();
        String thumbnailUrl = "";
        float rating = Float.NaN;
        int reviews;

        String photo() { return !thumbnailUrl.isEmpty() ? thumbnailUrl : photos.isEmpty() ? "" : photos.get(0); }

        /** Takes what [o] knows of the place and this does not. */
        void fillFrom(Place o) {
            if (o == null) return;
            if (category.isEmpty() && o.category != null) category = o.category;
            if (hours.isEmpty() && o.hours != null) hours = o.hours;
            if (photos.isEmpty()) photos.addAll(o.photos);
            if (Float.isNaN(rating)) rating = o.rating;
            if (reviews == 0) reviews = o.reviews;
        }
        double lat = Double.NaN, lng = Double.NaN;
        long added;
        final Set<String> lists = new LinkedHashSet<>();

        /** Has a position: a place from a Google Takeout list has none until Maps shows it. */
        boolean located() {
            return coordinates(lat, lng);
        }

        /** Unresolved and duplicate entries have identities independent of their coordinates. */
        String key() {
            if (separateEntry) return "id:" + id;
            if (ftid != null) return ftid;
            if (cid != null && !"0".equals(cid)) return "cid:" + cid;
            if (placeId != null) return "place:" + placeId;
            if (located()) return String.format(Locale.US, "%.6f,%.6f", lat, lng);
            return "id:" + id;
        }

        String listNote(String list) {
            return list == null ? note : listNotes.getOrDefault(list, "");
        }

        String listTitle(String list) {
            JSONObject entry = listEntries.get(list);
            return entry == null ? name : entry.optString("title", name);
        }

        String membershipId(String list) {
            String value = membershipIds.get(list);
            if (value == null) membershipIds.put(list, value = UUID.randomUUID().toString());
            return value;
        }

        Place displayCopy() {
            Place p = new Place();
            p.id = id; p.separateEntry = separateEntry; p.ftid = ftid; p.cid = cid; p.placeId = placeId; p.sourceUrl = sourceUrl;
            p.name = name; p.note = note; p.category = category; p.hours = hours;
            p.lat = lat; p.lng = lng; p.sharedLat = sharedLat; p.sharedLng = sharedLng;
            p.added = added; p.rating = rating; p.reviews = reviews; p.metadataAt = metadataAt;
            p.detailsState = detailsState; p.thumbnailState = thumbnailState; p.detailError = detailError;
            p.thumbnailError = thumbnailError; p.thumbnailUrl = thumbnailUrl;
            p.candidateFtid = candidateFtid; p.candidateName = candidateName;
            p.photos.addAll(photos); p.lists.addAll(lists); p.listNotes.putAll(listNotes);
            p.listEntries.putAll(listEntries);
            return p;
        }

        private void restoreFrom(Place p) {
            id = p.id; separateEntry = p.separateEntry; ftid = p.ftid; cid = p.cid; placeId = p.placeId; sourceUrl = p.sourceUrl;
            name = p.name; note = p.note; category = p.category; hours = p.hours;
            lat = p.lat; lng = p.lng; sharedLat = p.sharedLat; sharedLng = p.sharedLng;
            added = p.added; rating = p.rating; reviews = p.reviews; metadataAt = p.metadataAt;
            detailsState = p.detailsState; thumbnailState = p.thumbnailState; detailError = p.detailError;
            thumbnailError = p.thumbnailError; thumbnailUrl = p.thumbnailUrl;
            candidateFtid = p.candidateFtid; candidateName = p.candidateName;
            attempts = p.attempts; thumbnailAttempts = p.thumbnailAttempts; retryAt = p.retryAt;
            photos.clear(); photos.addAll(p.photos);
            lists.clear(); lists.addAll(p.lists);
            listNotes.clear(); listNotes.putAll(p.listNotes);
            listEntries.clear(); listEntries.putAll(p.listEntries);
            membershipIds.clear(); membershipIds.putAll(p.membershipIds);
            sources.clear(); sources.addAll(p.sources);
            importAliases.clear(); importAliases.addAll(p.importAliases);
            extra = p.extra;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject(extra.toString());
            o.put("id", id);
            if (separateEntry) o.put("separateEntry", true);
            if (ftid != null) o.put("ftid", ftid);
            if (cid != null) o.put("cid", cid);
            if (placeId != null) o.put("placeId", placeId);
            if (!sourceUrl.isEmpty()) o.put("sourceUrl", sourceUrl);
            o.put("name", name);
            if (!note.isEmpty()) o.put("note", note);
            if (!category.isEmpty()) o.put("category", category);
            if (!hours.isEmpty()) o.put("hours", hours);
            if (!photos.isEmpty()) o.put("photos", new JSONArray(photos));
            if (!thumbnailUrl.isEmpty()) o.put("thumbnailUrl", thumbnailUrl);
            if (!Float.isNaN(rating)) o.put("rating", (double) rating);
            if (reviews > 0) o.put("reviews", reviews);
            o.put("hasCoordinates", located());
            if (located()) { o.put("lat", lat); o.put("lng", lng); }
            if (coordinates(sharedLat, sharedLng)) {
                o.put("sharedLat", sharedLat); o.put("sharedLng", sharedLng);
            }
            o.put("added", added);
            o.put("lists", new JSONArray(lists));
            for (String list : lists) membershipId(list);
            o.put("membershipIds", new JSONObject(membershipIds));
            o.put("listNotes", new JSONObject(listNotes));
            o.put("listEntries", new JSONObject(listEntries));
            if (!sources.isEmpty()) o.put("sources", new JSONArray(sources));
            o.put("detailsState", detailsState).put("thumbnailState", thumbnailState);
            o.put("attempts", attempts).put("thumbnailAttempts", thumbnailAttempts).put("retryAt", retryAt).put("metadataAt", metadataAt);
            if (!detailError.isEmpty()) o.put("detailError", detailError);
            if (!thumbnailError.isEmpty()) o.put("thumbnailError", thumbnailError);
            if (!candidateFtid.isEmpty()) o.put("candidateFtid", candidateFtid).put("candidateName", candidateName);
            if (!importAliases.isEmpty()) o.put("importAliases", new JSONArray(importAliases));
            return o;
        }

        static Place fromJson(JSONObject o) throws Exception {
            Place p = new Place();
            p.extra = unknownFields(o, PLACE_FIELDS);
            p.id = o.optString("id", p.id);
            if (p.id.isEmpty()) throw new IOException("Empty place identifier");
            p.separateEntry = o.optBoolean("separateEntry");
            p.ftid = o.has("ftid") ? o.optString("ftid", null) : null;
            p.cid = o.has("cid") ? o.optString("cid", null) : null;
            if (p.ftid != null && p.ftid.startsWith("0x0:")) {
                p.cid = SavedPlaces.cid(p.ftid);
                p.ftid = null;
            }
            p.placeId = o.has("placeId") ? o.optString("placeId", null) : null;
            p.sourceUrl = o.optString("sourceUrl", "");
            p.name = o.optString("name", "");
            p.note = o.optString("note", "");
            p.category = o.optString("category", "");
            p.hours = o.optString("hours", "");
            readPhotos(o, p.photos);
            p.thumbnailUrl = o.optString("thumbnailUrl", "");
            p.rating = (float) o.optDouble("rating", Double.NaN);
            if (Float.isInfinite(p.rating)) throw new IOException("Invalid place rating");
            p.reviews = o.optInt("reviews", 0);
            p.lat = o.optDouble("lat", Double.NaN);
            p.lng = o.optDouble("lng", Double.NaN);
            if ((o.has("lat") || o.has("lng")) && !coordinates(p.lat, p.lng))
                throw new IOException("Invalid or incomplete place coordinates");
            if (o.has("hasCoordinates") ? !o.getBoolean("hasCoordinates") : p.lat == 0 && p.lng == 0) {
                p.lat = p.lng = Double.NaN;
            }
            p.sharedLat = o.optDouble("sharedLat", Double.NaN);
            p.sharedLng = o.optDouble("sharedLng", Double.NaN);
            if ((o.has("sharedLat") || o.has("sharedLng")) && !coordinates(p.sharedLat, p.sharedLng))
                throw new IOException("Invalid source coordinates");
            p.added = o.optLong("added", System.currentTimeMillis());
            JSONArray l = array(o, "lists");
            if (l != null) for (int i = 0; i < l.length(); i++) p.lists.add(l.getString(i));
            p.lists.remove(OLD_SAVED);
            readStrings(object(o, "membershipIds"), p.membershipIds);
            readStrings(object(o, "listNotes"), p.listNotes);
            JSONObject entries = object(o, "listEntries");
            if (entries != null) for (java.util.Iterator<String> it = entries.keys(); it.hasNext();) {
                String list = it.next();
                JSONObject entry = entries.getJSONObject(list);
                p.listEntries.put(list, entry);
            }
            JSONArray sources = array(o, "sources");
            if (sources != null) for (int i = 0; i < sources.length(); i++) p.sources.add(sources.getJSONObject(i));
            p.detailsState = o.optString("detailsState", "none");
            if ("loading".equals(p.detailsState)) p.detailsState = "pending";
            p.thumbnailState = o.optString("thumbnailState", "none");
            p.detailError = o.optString("detailError", "");
            p.thumbnailError = o.optString("thumbnailError", "");
            p.candidateFtid = o.optString("candidateFtid", "");
            p.candidateName = o.optString("candidateName", "");
            JSONArray aliases = array(o, "importAliases");
            if (aliases != null) for (int i = 0; i < aliases.length(); i++) p.importAliases.add(aliases.getString(i));
            p.attempts = Math.max(0, o.optInt("attempts"));
            p.thumbnailAttempts = Math.max(0, o.optInt("thumbnailAttempts"));
            p.retryAt = o.optLong("retryAt");
            p.metadataAt = o.optLong("metadataAt");
            for (String list : p.lists) p.membershipId(list);
            return p;
        }

        static Place imported(JSONObject o) throws Exception {
            Place p = fromJson(o);
            JSONObject original = new JSONObject(o.toString());
            original.remove("sources"); // Already retained separately; do not recursively wrap previous imports.
            String source = UUID.nameUUIDFromBytes(("backup\n" + original).getBytes(StandardCharsets.UTF_8)).toString();
            boolean known = false;
            for (JSONObject record : p.sources) if (source.equals(record.optString("id"))) { known = true; break; }
            if (!known) p.sources.add(new JSONObject().put("id", source).put("backup", original));
            p.photos.clear();
            p.thumbnailUrl = "";
            p.detailsState = p.thumbnailState = "none";
            p.detailError = p.thumbnailError = p.candidateFtid = p.candidateName = "";
            p.attempts = p.thumbnailAttempts = 0;
            p.retryAt = p.metadataAt = 0;
            return p;
        }
    }

    static boolean coordinates(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }

    private static JSONObject unknownFields(JSONObject source, String... known) throws Exception {
        Set<String> fields = new java.util.HashSet<>(java.util.Arrays.asList(known));
        JSONObject extra = new JSONObject();
        for (java.util.Iterator<String> it = source.keys(); it.hasNext();) {
            String key = it.next();
            if (!fields.contains(key)) extra.put(key, source.get(key));
        }
        return extra;
    }

    private static JSONArray array(JSONObject o, String key) throws Exception {
        return o.has(key) ? o.getJSONArray(key) : null;
    }

    private static JSONObject object(JSONObject o, String key) throws Exception {
        return o.has(key) ? o.getJSONObject(key) : null;
    }

    private static void readStrings(JSONObject object, Map<String, String> into) throws Exception {
        if (object == null) return;
        for (java.util.Iterator<String> it = object.keys(); it.hasNext();) {
            String key = it.next();
            into.put(key, object.getString(key));
        }
    }

    /** "photos": [...], or a first version's single "photo". */
    static void readPhotos(JSONObject o, List<String> into) {
        JSONArray a = o.optJSONArray("photos");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) { String u = a.optString(i, ""); if (!u.isEmpty()) into.add(u); }
        } else if (!o.optString("photo", "").isEmpty()) into.add(o.optString("photo"));
    }

    private static boolean loaded;
    private static boolean writeDisabled;
    private static long generation, persistedGeneration;
    private static volatile IOException writeError;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> new Thread(r, "UA-store"));
    static boolean enrichmentPaused;
    private static final Map<String, String> importedLists = new LinkedHashMap<>();
    private static JSONObject extra = new JSONObject();
    /** List id -> name, in display order. */
    static final Map<String, String> lists = new LinkedHashMap<>();
    static final Map<String, String> descriptions = new LinkedHashMap<>();
    private static final Map<String, JSONObject> listExtras = new LinkedHashMap<>();
    /** Key -> place, newest last. */
    static final Map<String, Place> places = new LinkedHashMap<>();
    static Place home, work;
    /** The user's own labels ("Gym", "Mum's"), label -> place: Maps' "Labeled" beside Home and Work. */
    static final Map<String, Place> labels = new LinkedHashMap<>();
    /** Lists whose places are not drawn on the map ("Hide on map"); every other list is. */
    static final Set<String> hiddenOnMap = new LinkedHashSet<>();
    /** List id -> the emoji its places are drawn with on the map (issue #128); lists of the user's own only. */
    static final Map<String, String> emojis = new java.util.HashMap<>();

    private SavedStore() {}

    private static void clearState() {
        lists.clear();
        descriptions.clear();
        listExtras.clear();
        places.clear();
        labels.clear();
        hiddenOnMap.clear();
        emojis.clear();
        importedLists.clear();
        extra = new JSONObject();
        home = work = null;
    }

    static synchronized void load(Context c) {
        if (loaded) return;
        clearState();
        boolean migrated = false;
        try {
            File f = new File(c.getFilesDir(), FILE);
            AtomicFile atomic = new AtomicFile(f);
            if (f.exists() || new File(f + ".bak").exists()) {
                JSONObject root;
                try (InputStream in = atomic.openRead()) { root = new JSONObject(readAll(in)); }
                int version = version(root);
                if (version < 3) {
                    migrated = true;
                    File backup = new File(c.getFilesDir(), FILE + ".v" + version + ".bak");
                    if (!backup.exists()) java.nio.file.Files.copy(f.toPath(), backup.toPath());
                }
                read(root, true);
                enrichmentPaused = root.optBoolean("enrichmentPaused", false);
            }
        } catch (Exception e) {
            writeDisabled = true;
            clearState();
            android.util.Log.e("UA", "Library could not be opened; original file retained", e);
            throw new IllegalStateException("Cannot read the saved library. The original and backups have not been overwritten.", e);
        }
        ensureDefaultLists();
        loaded = true;
        writeDisabled = false;
        if (migrated) {
            prepareEnrichment();
            save(c);
        }
    }

    static boolean isDefault(String id) {
        return WANT_TO_GO.equals(id) || TRAVEL.equals(id) || STARRED.equals(id) || FAVOURITES.equals(id);
    }

    /** Maps' four lists first, in Maps' order, then the user's own. */
    private static void ensureDefaultLists() {
        lists.remove(OLD_SAVED);
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(WANT_TO_GO, "Want to go");
        defaults.put(TRAVEL, "Travel plans");
        defaults.put(STARRED, "Starred places");
        defaults.put(FAVOURITES, "Favorites");
        for (Map.Entry<String, String> e : lists.entrySet()) if (!defaults.containsKey(e.getKey())) defaults.put(e.getKey(), e.getValue());
        // A default list keeps a name the user gave it; the first version's "Favourites" is not one.
        for (Map.Entry<String, String> e : lists.entrySet()) {
            if (defaults.containsKey(e.getKey()) && !"Favourites".equals(e.getValue())) defaults.put(e.getKey(), e.getValue());
        }
        lists.clear();
        lists.putAll(defaults);
    }

    /** Reads our own format; [replace] false merges into what is already there. */
    private static void read(JSONObject root, boolean replace) throws Exception {
        version(root);
        JSONObject unknown = unknownFields(root, "app", "version", "lists", "places", "home", "work",
                "labels", "hiddenOnMap", "history", "enrichmentPaused", "importedLists");
        if (replace) extra = unknown;
        else mergeExtra(extra, unknown);
        JSONArray l = array(root, "lists");
        if (l != null) for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.getJSONObject(i);
            String id = o.getString("id");
            if (id.isEmpty()) throw new IOException("Empty list identifier");
            if (replace || !lists.containsKey(id)) lists.put(id, o.getString("name"));
            String emoji = o.optString("emoji", "");
            if (!emoji.isEmpty() && (replace || !emojis.containsKey(id))) emojis.put(id, emoji);
            String description = o.optString("description", "");
            if (o.has("description") && (replace || !descriptions.containsKey(id))) descriptions.put(id, description);
            JSONObject more = unknownFields(o, "id", "name", "emoji", "description");
            if (replace || !listExtras.containsKey(id)) listExtras.put(id, more);
            else mergeExtra(listExtras.get(id), more);
        }
        JSONArray ps = array(root, "places");
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            JSONObject record = ps.getJSONObject(i);
            Place p = replace ? Place.fromJson(record) : Place.imported(record);
            if (!record.has("id")) p.id = UUID.nameUUIDFromBytes(("backup\n" + i + "\n" + record)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            if (replace) {
                if (find(p.id) != null) throw new IOException("Duplicate stored place identifier");
                if (places.containsKey(p.key())) p.separateEntry = true;
                places.put(p.key(), p);
            } else merge(p);
        }
        if (root.has("home")) {
            Place p = replace ? Place.fromJson(root.getJSONObject("home")) : Place.imported(root.getJSONObject("home"));
            if (replace || home == null) home = p;
        }
        if (root.has("work")) {
            Place p = replace ? Place.fromJson(root.getJSONObject("work")) : Place.imported(root.getJSONObject("work"));
            if (replace || work == null) work = p;
        }
        JSONArray lb = array(root, "labels");
        if (lb != null) for (int i = 0; i < lb.length(); i++) {
            JSONObject o = lb.getJSONObject(i);
            String label = o.getString("label");
            Place p = replace ? Place.fromJson(o.getJSONObject("place")) : Place.imported(o.getJSONObject("place"));
            if (label.isEmpty()) throw new IOException("Empty label");
            if (replace || !labels.containsKey(label)) labels.put(label, p);
        }
        JSONArray hidden = array(root, "hiddenOnMap");
        if (hidden != null) for (int i = 0; i < hidden.length(); i++) hiddenOnMap.add(hidden.getString(i));
        Map<String, String> imported = new LinkedHashMap<>();
        readStrings(object(root, "importedLists"), imported);
        for (Map.Entry<String, String> e : imported.entrySet()) importedLists.putIfAbsent(e.getKey(), e.getValue());
    }

    private static int version(JSONObject root) throws Exception {
        if (!root.has("version")) return 1;
        Object value = root.get("version");
        if (!(value instanceof Number)) throw new IOException("Unsupported backup version; original file retained");
        double version = ((Number) value).doubleValue();
        if (version != 1 && version != 2 && version != 3)
            throw new IOException("Unsupported backup version; original file retained");
        return (int) version;
    }

    private static void mergeExtra(JSONObject into, JSONObject from) throws Exception {
        for (java.util.Iterator<String> it = from.keys(); it.hasNext();) {
            String key = it.next();
            if (into.has(key) && !String.valueOf(into.get(key)).equals(String.valueOf(from.get(key))))
                throw new IOException("Conflicting unsupported field: " + key + "; original data retained");
            into.put(key, from.get(key));
        }
    }

    private static void merge(Place p) throws Exception {
        Place old = matching(p);
        boolean sameEntry = old != null && (old.id.equals(p.id) || old.importAliases.contains("id:" + p.id));
        if (old != null && !sameEntry && (!java.util.Collections.disjoint(old.lists, p.lists) ||
                old.lists.isEmpty() && p.lists.isEmpty())) {
            p.separateEntry = true;
            old = null;
        }
        if (old == null) {
            if (places.containsKey(p.key())) p.separateEntry = true;
            places.put(p.key(), p);
        } else {
            String oldKey = old.key();
            mergeExtra(old.extra, p.extra);
            old.lists.addAll(p.lists);
            old.importAliases.add("id:" + p.id);
            old.importAliases.addAll(p.importAliases);
            old.fillFrom(p);
            if (old.note.isEmpty()) old.note = p.note;
            if (old.name == null || old.name.isEmpty()) old.name = p.name;
            if (old.sourceUrl.isEmpty()) old.sourceUrl = p.sourceUrl;
            if (!old.located() && p.located()) { old.lat = p.lat; old.lng = p.lng; }
            if (!coordinates(old.sharedLat, old.sharedLng) && coordinates(p.sharedLat, p.sharedLng)) {
                old.sharedLat = p.sharedLat; old.sharedLng = p.sharedLng;
            }
            for (Map.Entry<String, String> e : p.listNotes.entrySet()) old.listNotes.putIfAbsent(e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : p.membershipIds.entrySet()) old.membershipIds.putIfAbsent(e.getKey(), e.getValue());
            for (Map.Entry<String, JSONObject> e : p.listEntries.entrySet()) old.listEntries.putIfAbsent(e.getKey(), e.getValue());
            Set<String> seen = new LinkedHashSet<>();
            for (JSONObject s : old.sources) seen.add(s.optString("id"));
            for (JSONObject s : p.sources) if (seen.add(s.optString("id"))) old.sources.add(s);
            if (!oldKey.equals(old.key())) {
                places.remove(oldKey);
                if (places.containsKey(old.key())) old.separateEntry = true;
                places.put(old.key(), old);
            }
        }
    }

    private static Place matching(Place p) {
        for (Place q : places.values()) {
            if (q.id.equals(p.id) || q.importAliases.contains("id:" + p.id)) return q;
        }
        Place exact = places.get(p.key());
        if (exact != null) return exact;
        String cid = p.cid != null ? p.cid : SavedPlaces.cid(p.ftid);
        for (Place q : places.values()) {
            if (p.separateEntry || q.separateEntry) continue;
            if (q.importAliases.contains(p.key())) return q;
            if (cid != null && !"0".equals(cid) && cid.equals(q.cid != null ? q.cid : SavedPlaces.cid(q.ftid))) return q;
            if (p.placeId != null && p.placeId.equals(q.placeId)) return q;
        }
        return null;
    }

    static synchronized void save(Context c) {
        if (writeDisabled) throw new IllegalStateException("Library is read-only after an error; original files retained");
        generation++;
        Context app = c.getApplicationContext();
        WRITER.execute(() -> {
            try {
                persist(app);
            } catch (IOException e) {
                boolean first = writeError == null;
                writeError = e;
                android.util.Log.e("UA", "Library changes could not be saved", e);
                if (first) new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(app, "Library could not be saved. Check free space and retry.", android.widget.Toast.LENGTH_LONG).show());
            }
        });
        SavedOnMap.refresh();
    }

    private static void persist(Context c) throws IOException {
        byte[] bytes;
        long at;
        synchronized (SavedStore.class) {
            if (writeDisabled) throw new IOException("Library is read-only; original file retained");
            if (persistedGeneration == generation) return;
            at = generation;
            try { bytes = toJson().toString().getBytes(StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IOException("Cannot encode saved library", e); }
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Library exceeds 64 MiB; original checkpoint retained");
        }
        AtomicFile file = new AtomicFile(new File(c.getFilesDir(), FILE));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            out.getFD().sync();
            file.finishWrite(out);
            out = null;
            if (!java.util.Arrays.equals(bytes, file.readFully())) throw new IOException("Library checkpoint could not be verified");
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
        synchronized (SavedStore.class) { persistedGeneration = at; }
        writeError = null;
    }

    static void flush() throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("Library flush must run off the main thread");
        }
        WRITER.submit(() -> {}).get();
        if (writeError != null) throw writeError;
    }

    static synchronized JSONObject toJson() throws Exception {
        JSONObject root = new JSONObject(extra.toString());
        root.put("app", "Ungoogled Maps");
        root.put("version", 3);
        root.put("enrichmentPaused", enrichmentPaused);
        root.put("importedLists", new JSONObject(importedLists));
        JSONArray l = new JSONArray();
        for (Map.Entry<String, String> e : lists.entrySet()) {
            JSONObject more = listExtras.get(e.getKey());
            JSONObject o = more == null ? new JSONObject() : new JSONObject(more.toString());
            o.put("id", e.getKey()).put("name", e.getValue());
            if (emojis.containsKey(e.getKey())) o.put("emoji", emojis.get(e.getKey()));
            if (descriptions.containsKey(e.getKey())) o.put("description", descriptions.get(e.getKey()));
            l.put(o);
        }
        root.put("lists", l);
        JSONArray ps = new JSONArray();
        for (Place p : places.values()) ps.put(p.toJson());
        root.put("places", ps);
        if (home != null) root.put("home", home.toJson());
        if (work != null) root.put("work", work.toJson());
        if (!labels.isEmpty()) {
            JSONArray lb = new JSONArray();
            for (Map.Entry<String, Place> e : labels.entrySet()) lb.put(new JSONObject().put("label", e.getKey()).put("place", e.getValue().toJson()));
            root.put("labels", lb);
        }
        if (!hiddenOnMap.isEmpty()) root.put("hiddenOnMap", new JSONArray(hiddenOnMap));
        return root;
    }

    /** Draws [listId]'s places on the map, or not ("Hide on map"). */
    static synchronized void setOnMap(Context c, String listId, boolean shown) {
        if (shown ? hiddenOnMap.remove(listId) : hiddenOnMap.add(listId)) save(c);
    }

    static synchronized long revision() { return generation; }

    static synchronized Map<String, String> listNames() { return new LinkedHashMap<>(lists); }

    static synchronized Map<String, Place> labeledPlaces() { return new LinkedHashMap<>(labels); }

    // ---- queries and edits ------------------------------------------------------

    static synchronized Place find(String key) {
        if (key == null) return null;
        Place found = places.get(key);
        if (found != null) return found;
        String cid = key.startsWith("cid:") ? key.substring(4) : SavedPlaces.cid(key);
        for (Place p : places.values()) {
            if (key.equals(p.id) || p.importAliases.contains(key) ||
                    cid != null && !"0".equals(cid) && cid.equals(p.cid) ||
                    key.startsWith("place:") && key.substring(6).equals(p.placeId)) return p;
        }
        return null;
    }

    static synchronized List<Place> inList(String listId) {
        List<Place> out = new ArrayList<>();
        for (Place p : places.values()) if (p.lists.contains(listId)) out.add(p);
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out;
    }

    /** Save: keeps [p] (in no list yet) unless it is already kept. Returns the stored place. */
    static synchronized Place keep(Context c, Place p) {
        Place stored = matching(p);
        if (stored != null) return stored;
        p.added = System.currentTimeMillis();
        places.put(p.key(), p);
        save(c);
        return p;
    }

    /** Unsave: forgets [p] and its lists and note. */
    static synchronized void unsave(Context c, Place p) {
        Place stored = matching(p);
        if (stored == null) return;
        places.remove(stored.key());
        save(c);
    }

    /** Puts a saved place in exactly [listIds] (none is fine: it stays saved). */
    static synchronized void setLists(Context c, Place p, Set<String> listIds) {
        Place stored = keep(c, p);
        for (String old : new LinkedHashSet<>(stored.lists)) if (!listIds.contains(old)) {
            stored.membershipIds.remove(old);
            stored.listEntries.remove(old);
            stored.listNotes.remove(old);
        }
        stored.lists.clear();
        stored.lists.addAll(listIds);
        for (String list : listIds) stored.membershipId(list);
        save(c);
    }

    static synchronized void setMembership(Context c, Place p, String list, boolean included) {
        Place stored = keep(c, p);
        Set<String> next = new LinkedHashSet<>(stored.lists);
        if (included) next.add(list); else next.remove(list);
        setLists(c, stored, next);
    }

    static synchronized void setNote(Context c, Place p, String note) {
        Place stored = matching(p);
        if (stored == null) return;
        stored.note = note == null ? "" : note.trim();
        save(c);
    }

    static synchronized void setListNote(Context c, Place p, String list, String note) {
        Place stored = matching(p);
        if (stored == null || !stored.lists.contains(list)) throw new IllegalArgumentException("Place is not in this list");
        stored.listNotes.put(list, note == null ? "" : note.trim());
        save(c);
    }

    static synchronized int countIn(String listId) {
        int n = 0;
        for (Place p : places.values()) if (p.lists.contains(listId)) n++;
        return n;
    }

    /** Every saved place, newest first. */
    static synchronized List<Place> allSaved() {
        List<Place> out = new ArrayList<>(places.values());
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out;
    }

    /** Saved places that are in no list, newest first. */
    static synchronized List<Place> unlisted() {
        List<Place> out = new ArrayList<>();
        for (Place p : places.values()) if (p.lists.isEmpty()) out.add(p);
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out;
    }

    static synchronized String addList(Context c, String name, String emoji) {
        String id = UUID.randomUUID().toString();
        lists.put(id, name);
        if (emoji != null && !emoji.isEmpty()) emojis.put(id, emoji);
        save(c);
        return id;
    }

    /** A new name and, for a list of the user's own, emoji (null or "" for the list icon). */
    static synchronized void editList(Context c, String id, String name, String emoji) {
        if (!lists.containsKey(id)) return;
        lists.put(id, name);
        if (emoji == null || emoji.isEmpty() || isDefault(id)) emojis.remove(id);
        else emojis.put(id, emoji);
        save(c);
    }

    /** Deletes a list of the user's own; its places stay saved. */
    static synchronized void deleteList(Context c, String id) {
        if (isDefault(id)) return;
        lists.remove(id);
        descriptions.remove(id);
        listExtras.remove(id);
        emojis.remove(id);
        for (Place p : places.values()) {
            p.lists.remove(id);
            p.listNotes.remove(id);
            p.membershipIds.remove(id);
            p.listEntries.remove(id);
        }
        save(c);
    }

    /**
     * microG Maps' Pull from Google account: [names] are the account's own lists (id here -> name),
     * added or renamed; [found] its saved places, each in the lists it is in. A place already here
     * joins those lists and keeps its own note unless it has none; nothing here is removed.
     * Returns how many places are new.
     */
    static synchronized int mergePulled(Context c, Map<String, String> names, List<Place> found) {
        load(c);
        lists.putAll(names);
        int added = 0;
        for (Place p : found) {
            Place old = matching(p);
            if (old == null) {
                places.put(p.key(), p);
                added++;
                continue;
            }
            old.lists.addAll(p.lists);
            if (old.note.isEmpty()) old.note = p.note;
            if (!old.located() && p.located()) { old.lat = p.lat; old.lng = p.lng; }
        }
        ensureDefaultLists();
        save(c);
        return added;
    }

    static synchronized void setHome(Context c, Place p) { home = p; save(c); }
    static synchronized void setWork(Context c, Place p) { work = p; save(c); }

    /** Gives [p] the label [label] (a label names one place; giving it again moves it). */
    static synchronized void setLabel(Context c, String label, Place p) {
        labels.put(label, aliasOf(p));
        save(c);
    }

    static synchronized void removeLabel(Context c, String label) {
        if (labels.remove(label) != null) save(c);
    }

    /** The place labelled [label] -- Home, Work or one of the user's own -- ignoring case; null if none. */
    static synchronized Place labelled(String label) {
        if (label.equalsIgnoreCase("home")) return home;
        if (label.equalsIgnoreCase("work")) return work;
        for (Map.Entry<String, Place> e : labels.entrySet()) if (e.getKey().equalsIgnoreCase(label)) return e.getValue();
        return null;
    }

    /** The user's own labels on [p]. */
    static synchronized List<String> labelsFor(Place p) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Place> e : labels.entrySet()) if (same(e.getValue(), p)) out.add(e.getKey());
        return out;
    }

    /** A copy of [p] to keep as Home or Work: the spot and its name, none of its lists or note. */
    static Place aliasOf(Place p) {
        Place c = new Place();
        c.id = p.id; c.ftid = p.ftid; c.cid = p.cid; c.placeId = p.placeId; c.sourceUrl = p.sourceUrl;
        c.name = p.name; c.lat = p.lat; c.lng = p.lng; c.added = System.currentTimeMillis();
        c.fillFrom(p);
        return c;
    }

    static boolean same(Place a, Place b) {
        if (a == null || b == null) return false;
        if (a.id.equals(b.id) || a.key().equals(b.key())) return true;
        String cid = a.cid != null ? a.cid : SavedPlaces.cid(a.ftid);
        return cid != null && !"0".equals(cid) && cid.equals(b.cid != null ? b.cid : SavedPlaces.cid(b.ftid))
                || a.placeId != null && a.placeId.equals(b.placeId);
    }

    // ---- export / import ------------------------------------------------------------

    /** Everything, recent places included: the file Import takes back. */
    static void exportJson(Context c, Uri to) throws Exception {
        JSONObject root = toJson();
        HistoryStore.load(c);
        root.put("history", HistoryStore.toJson());
        write(c, to, root.toString(1));
    }

    /** The recent places as KML, newest first. */
    static void exportRecentKml(Context c, Uri to) throws Exception {
        HistoryStore.load(c);
        StringBuilder k = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Ungoogled Maps recent places</name>\n"
                + "<Folder><name>Recent places</name>\n");
        for (HistoryStore.Entry e : HistoryStore.newestFirst()) kmlPlace(k, e.asPlace(), e.name);
        k.append("</Folder></Document></kml>\n");
        write(c, to, k.toString());
    }

    /** KML with one folder per list, Home and Work: what OsmAnd, Organic Maps and Google Earth open. */
    static void exportKml(Context c, Uri to) throws Exception {
        StringBuilder k = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Ungoogled Maps saved places</name>\n");
        synchronized (SavedStore.class) {
            if (home != null || work != null) {
                k.append("<Folder><name>Home and Work</name>\n");
                if (home != null) kmlPlace(k, home, "Home: " + home.name);
                if (work != null) kmlPlace(k, work, "Work: " + work.name);
                k.append("</Folder>\n");
            }
            if (!labels.isEmpty()) {
                k.append("<Folder><name>Labeled</name>\n");
                for (Map.Entry<String, Place> e : labels.entrySet()) kmlPlace(k, e.getValue(), e.getKey() + ": " + e.getValue().name);
                k.append("</Folder>\n");
            }
            List<Place> loose = unlisted();
            if (!loose.isEmpty()) {
                k.append("<Folder><name>Saved places</name>\n");
                for (Place p : loose) kmlPlace(k, p, p.name);
                k.append("</Folder>\n");
            }
            for (Map.Entry<String, String> e : lists.entrySet()) {
                List<Place> in = inList(e.getKey());
                if (in.isEmpty()) continue;
                k.append("<Folder><name>").append(xml(e.getValue())).append("</name>\n");
                for (Place p : in) kmlPlace(k, p, p.name);
                k.append("</Folder>\n");
            }
        }
        k.append("</Document></kml>\n");
        write(c, to, k.toString());
    }

    private static void kmlPlace(StringBuilder k, Place p, String name) {
        if (!p.located()) return;   // a Takeout list's place Maps has not shown yet
        k.append("<Placemark><name>").append(xml(name)).append("</name><Point><coordinates>")
                .append(String.format(Locale.US, "%.7f,%.7f", p.lng, p.lat)).append("</coordinates></Point></Placemark>\n");
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void write(Context c, Uri to, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Export exceeds the 64 MiB import limit");
        try (OutputStream out = c.getContentResolver().openOutputStream(to, "wt")) {
            if (out == null) throw new java.io.IOException("cannot write " + to);
            out.write(bytes);
        }
    }

    /** What an import added: places, new lists, places still without a position, and files read. */
    static final class Imported {
        int places, lists, unlocated, files;
        final List<String> problems = new ArrayList<>();
    }

    /**
     * Merges files into the store: our own export, GeoJSON (Google Takeout's "Saved
     * Places.json" is one), KML (folders become lists), Google Takeout's saved lists (one
     * CSV per list, named after the list) or a whole Takeout .zip, whose Saved lists and
     * Saved Places.json are all taken.
     */
    static Imported importFiles(Context c, List<Uri> from) throws Exception {
        load(c);
        Imported r = new Imported();
        int before;
        Set<String> listsBefore;
        synchronized (SavedStore.class) {
            before = places.size();
            listsBefore = new LinkedHashSet<>(lists.keySet());
        }
        for (Uri uri : from) {
            try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new java.io.IOException("cannot read " + uri);
                java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw);
                in.mark(4);
                byte[] head = new byte[4];
                int n = in.read(head);
                in.reset();
                if (n == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) importZip(c, in, r);
                else {
                    importText(c, readAll(in), displayName(c, uri));
                    r.files++;
                }
            } catch (Exception e) {
                android.util.Log.w("UA", "Import file could not be read", e);
                r.problems.add(displayName(c, uri) + ": " + e.getMessage());
            }
        }
        // A place looked at in Maps recently has its position (and photos) in the recent places already.
        HistoryStore.load(c);
        synchronized (SavedStore.class) {
            for (Place p : places.values()) {
                if (p.located()) continue;
                HistoryStore.Entry seen = HistoryStore.find(p.key());
                if (seen == null) continue;
                Place known = seen.asPlace();
                p.fillFrom(known);
                if (known.located()) { p.lat = known.lat; p.lng = known.lng; }
            }
            ensureDefaultLists();
            r.places = places.size() - before;
            for (String id : lists.keySet()) if (!listsBefore.contains(id)) r.lists++;
            for (Place p : places.values()) if (!p.located()) r.unlocated++;
            prepareEnrichment();
        }
        save(c);
        flush();
        return r;
    }

    private static void prepareEnrichment() {
        for (Place p : places.values()) {
            if (!"none".equals(p.detailsState)) continue;
            if (!p.photos.isEmpty() && "none".equals(p.thumbnailState)) p.thumbnailState = "pending";
            if (p.ftid != null || p.cid != null && !"0".equals(p.cid) || p.placeId != null || isShortMapsLink(p.sourceUrl)) {
                p.detailsState = "pending";
            } else {
                p.detailsState = "unavailable";
                p.thumbnailState = p.photos.isEmpty() ? "unavailable" : "pending";
                p.detailError = p.located() ? "Coordinate-only entry; no verified place listing"
                        : "No supported Maps identifier. Original import row retained.";
            }
        }
    }

    /** One file's text, by what it holds; [name] is its file name, which names a Takeout list. */
    static void importText(Context c, String text, String name) throws Exception {
        String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
        t = t.trim();
        synchronized (SavedStore.class) {
            JSONObject before = toJson();
            Map<String, Place> originalPlaces = new LinkedHashMap<>(places);
            try {
                if (t.startsWith("{")) {
                    JSONObject root = new JSONObject(t);
                    if (root.has("format")) ListTransfer.importDocument(root);
                    else if (root.has("places") || root.has("lists") || root.has("history")) {
                        JSONArray history = array(root, "history");
                        if (history != null) HistoryStore.validateImport(history);
                        read(root, false);
                        if (history != null) HistoryStore.merge(c, history);
                    } else importGeoJson(root);
                } else if (t.startsWith("<")) {
                    importKml(c, t);
                } else {
                    importCsv(t, name);
                }
            } catch (Exception e) {
                clearState();
                read(before, true);
                // Requests and open Save sheets may still hold these objects.
                for (Place original : originalPlaces.values()) original.restoreFrom(find(original.id));
                places.clear();
                places.putAll(originalPlaces);
                JSONArray records = before.getJSONArray("places");
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.getJSONObject(i);
                    find(record.getString("id")).detailsState = record.optString("detailsState", "none");
                }
                throw e;
            }
        }
    }

    /** A Takeout .zip: every saved list (Takeout/Saved/<list>.csv) and the starred places (Saved Places.json). */
    private static void importZip(Context c, InputStream in, Imported r) throws Exception {
        boolean found = false;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String path = e.getName().replace('\\', '/');
                String file = path.substring(path.lastIndexOf('/') + 1);
                String lower = ("/" + path).toLowerCase(Locale.ROOT);
                boolean list = lower.endsWith(".csv") && lower.contains("/saved/");
                boolean starred = file.equalsIgnoreCase("Saved Places.json");
                if (!list && !starred) continue;
                found = true;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = zip.read(buf)) > 0; ) {
                    if (b.size() + n > MAX_FILE_BYTES) throw new IOException("Saved-list file exceeds 64 MiB");
                    b.write(buf, 0, n);
                }
                try {
                    importText(c, b.toString("UTF-8"), file);
                    r.files++;
                } catch (Exception problem) {
                    android.util.Log.w("UA", "Saved file inside ZIP could not be imported", problem);
                    r.problems.add(path + ": " + problem.getMessage());
                }
            }
        }
        if (!found) throw new IOException("No Saved-list CSV or Saved Places.json was found in the ZIP");
    }

    private static String displayName(Context c, Uri uri) {
        try (android.database.Cursor q = c.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    // ---- Google Takeout's saved lists ------------------------------------------------------

    private static final java.util.regex.Pattern FEATURE_ID = java.util.regex.Pattern.compile("(?:!1s|[?&]ftid=)(0x[0-9a-fA-F]+):(0x[0-9a-fA-F]+)");
    private static final java.util.regex.Pattern CID = java.util.regex.Pattern.compile("[?&](?:cid|ludocid)=(\\d+)");
    private static final java.util.regex.Pattern PLACE_ID = java.util.regex.Pattern.compile("!1s(ChIJ[a-zA-Z0-9_-]+)");
    private static final java.util.regex.Pattern PIN = java.util.regex.Pattern.compile("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)");
    private static final java.util.regex.Pattern SPOT = java.util.regex.Pattern.compile(
            "(?:/search/|/place/|[?&](?:q|query|destination)=|^geo:)\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*\\+?(-?\\d+(?:\\.\\d+)?)");

    /**
     * One saved list from Google Takeout: a CSV (Title, Note, URL, Tags, Comment) whose links
     * name each place by Maps' feature id -- no position, which the place takes from Maps the
     * first time Maps shows it -- or, for a dropped pin, by its coordinates.
     */
    private static void importCsv(String text, String fileName) throws Exception {
        List<List<String>> rows = csv(text);
        if (rows.isEmpty()) return;
        List<String> header = rows.get(0);
        int title = column(header, -1, "title"), note = column(header, -1, "note"), url = column(header, -1, "url"),
                comment = column(header, -1, "comment");
        if (title < 0 || url < 0) throw new IOException("Expected a saved-list CSV with Title and URL columns");
        String list = listForCsv(fileName);
        long now = System.currentTimeMillis();
        // The list's first row on top here too: the store keeps its newest last.
        for (int i = rows.size() - 1; i >= 1; i--) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).trim().isEmpty()) continue;
            String link = cell(row, url);
            // A link written without quotes splits at its commas (a dropped pin's "lat,lng", a
            // viewport's "@lat,lng,zoom"): the extra cells are its pieces.
            int extra = row.size() - header.size();
            if (extra > 0 && link.startsWith("http")) {
                StringBuilder joined = new StringBuilder(link);
                for (int k = 1; k <= extra; k++) joined.append(',').append(cell(row, url + k));
                link = joined.toString();
            }
            Place p = fromMapsLink(link, cell(row, title));
            String n = cell(row, note), m = cell(row, comment < 0 ? -1 : comment + Math.max(0, extra));
            String annotation = n.isEmpty() ? m : m.isEmpty() || m.equals(n) ? n : n + "\n" + m;
            if (list != null) {
                p.lists.add(list);
                p.listNotes.put(list, annotation);
                p.listEntries.put(list, ListTransfer.entry(p, list));
            } else p.note = annotation;
            String sourceId = UUID.nameUUIDFromBytes((fileName + "\n" + i + "\n" + new JSONArray(row))
                    .getBytes(StandardCharsets.UTF_8)).toString();
            p.id = sourceId;
            p.sources.add(new JSONObject().put("id", sourceId).put("file", fileName).put("row", i)
                    .put("headers", new JSONArray(header)).put("values", new JSONArray(row)));
            p.added = now - i;
            merge(p);
        }
    }

    private static int column(List<String> header, int fallback, String name) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        return fallback;
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** A place from a Maps link: its feature id (or customer id), else the spot a dropped pin's link names. */
    static Place fromMapsLink(String url, String title) {
        Place p = new Place();
        p.sourceUrl = url == null ? "" : url.trim();
        String link = Uri.decode(url == null ? "" : url);
        java.util.regex.Matcher m = FEATURE_ID.matcher(link);
        if (m.find()) {
            try {
                p.ftid = "0x" + Long.toHexString(Long.parseUnsignedLong(m.group(1).substring(2), 16))
                        + ":0x" + Long.toHexString(Long.parseUnsignedLong(m.group(2).substring(2), 16));
            } catch (NumberFormatException e) { p.detailError = "Invalid feature identifier"; }
        }
        if (p.ftid != null && p.ftid.startsWith("0x0:")) {
            p.cid = SavedPlaces.cid(p.ftid);
            p.ftid = null;
        }
        if (p.ftid == null && (m = CID.matcher(link)).find()) {
            try {
                p.cid = Long.toUnsignedString(Long.parseUnsignedLong(m.group(1)));
            } catch (NumberFormatException e) { p.detailError = "Invalid CID"; }
        }
        Uri parsed = Uri.parse(url == null ? "" : url);
        if (parsed.isHierarchical()) {
            p.placeId = parsed.getQueryParameter("query_place_id");
            if (p.placeId == null) {
                String query = parsed.getQueryParameter("q");
                if (query == null) query = parsed.getQueryParameter("query");
                if (query != null && query.startsWith("place_id:")) p.placeId = query.substring("place_id:".length());
            }
        }
        if (p.placeId == null && (m = PLACE_ID.matcher(link)).find()) p.placeId = m.group(1);
        // A position only where the link really names the spot: a place's own (!3d!4d), or a dropped pin's.
        m = PIN.matcher(link);
        boolean spot = m.find();
        if (!spot && p.ftid == null && p.cid == null && p.placeId == null) {
            m = SPOT.matcher(link);
            spot = m.find();
        }
        if (spot) {
            try {
                double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
                if (coordinates(lat, lng)) { p.lat = lat; p.lng = lng; }
                else p.detailError = "Coordinates are outside the valid range";
            } catch (NumberFormatException e) { p.detailError = "Invalid coordinates"; }
        }
        if (p.located()) { p.sharedLat = p.lat; p.sharedLng = p.lng; }
        String name = title == null ? "" : title.trim();
        if (name.isEmpty()) {
            int at = link.indexOf("/place/");
            if (at >= 0) {
                String rest = link.substring(at + 7);
                int end = rest.indexOf('/');
                name = (end >= 0 ? rest.substring(0, end) : rest).replace('+', ' ').trim();
            }
        }
        p.name = name.isEmpty() ? p.located() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : "Unresolved place" : name;
        return p;
    }

    static boolean isShortMapsLink(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        return "https".equals(uri.getScheme()) && ("maps.app.goo.gl".equalsIgnoreCase(uri.getHost())
                || "goo.gl".equalsIgnoreCase(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/maps/"));
    }

    /** The list a Takeout CSV goes into: Maps' own four by their Takeout names, any other by its name. */
    private static String listForCsv(String fileName) {
        String name = fileName == null ? "" : fileName.replaceAll("(?i)\\.csv$", "").trim();
        switch (name.toLowerCase(Locale.ROOT)) {
            case "favorite places": case "favourite places": case "favorites": case "favourites": return FAVOURITES;
            case "want to go": return WANT_TO_GO;
            case "travel plans": return TRAVEL;
            case "starred places": return STARRED;
            case "": return null;
        }
        String old = importedLists.get(fileName);
        if (old != null && lists.containsKey(old)) return old;
        String id = UUID.randomUUID().toString();
        lists.put(id, name);
        importedLists.put(fileName, id);
        return id;
    }

    /** RFC 4180: quoted fields may hold commas, line breaks and doubled quotes. */
    static List<List<String>> csv(String s) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch != '"') f.append(ch);
                else if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
                else quoted = false;
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(f.toString());
                f.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                f.append(ch);
            }
        }
        if (quoted) throw new IOException("Unterminated quoted CSV field; file not imported");
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Maps showed [p] in detail: the saved place of the same key takes what it lacks, its position too. */
    static synchronized void refresh(Context c, Place p) {
        Place stored = matching(p);
        if (stored == null) return;
        boolean changed = false;
        if (stored.category.isEmpty() && p.category != null && !p.category.isEmpty()) changed = true;
        if (stored.photos.isEmpty() && !p.photos.isEmpty()) changed = true;
        if (Float.isNaN(stored.rating) && !Float.isNaN(p.rating)) changed = true;
        if (stored.reviews == 0 && p.reviews > 0) changed = true;
        stored.fillFrom(p);
        if (!stored.located() && p.located()) {
            stored.lat = p.lat;
            stored.lng = p.lng;
            changed = true;
        }
        if (changed) save(c);
    }

    static synchronized Place applyDetails(Place target, Place details) {
        if (find(target.id) == null) throw new IllegalArgumentException("Saved place no longer exists");
        places.remove(target.key());
        target.ftid = details.ftid;
        target.cid = SavedPlaces.cid(details.ftid);
        if (places.containsKey(target.key())) target.separateEntry = true;
        places.put(target.key(), target);
        if ("Unresolved place".equals(target.name) || target.name == null || target.name.isEmpty()) target.name = details.name;
        target.lat = details.lat;
        target.lng = details.lng;
        target.category = details.category;
        target.hours = details.hours;
        target.rating = details.rating;
        target.reviews = details.reviews;
        target.photos.clear();
        target.photos.addAll(details.photos);
        target.thumbnailUrl = "";
        target.metadataAt = System.currentTimeMillis();
        target.detailsState = "complete";
        target.detailError = target.thumbnailError = target.candidateFtid = target.candidateName = "";
        target.thumbnailState = target.photos.isEmpty() ? "unavailable" : "pending";
        target.thumbnailAttempts = 0;
        return target;
    }

    static synchronized void confirmReplacement(Context c, Place p, String expectedFtid) {
        Place stored = find(p.id);
        if (stored == null || stored.candidateFtid.isEmpty() || !stored.candidateFtid.equals(expectedFtid))
            throw new IllegalStateException("The proposed replacement changed; review it again");
        String cid = SavedPlaces.cid(stored.candidateFtid);
        if (cid == null) throw new IllegalStateException("Invalid proposed replacement");
        stored.importAliases.add(stored.key());
        places.remove(stored.key());
        stored.ftid = stored.candidateFtid;
        stored.cid = cid;
        if (places.containsKey(stored.key())) stored.separateEntry = true;
        places.put(stored.key(), stored);
        stored.lat = stored.lng = Double.NaN;
        stored.photos.clear(); stored.thumbnailUrl = ""; stored.category = ""; stored.hours = "";
        stored.rating = Float.NaN; stored.reviews = 0; stored.metadataAt = 0;
        stored.detailsState = "pending"; stored.thumbnailState = "none"; stored.retryAt = 0;
        stored.attempts = stored.thumbnailAttempts = 0;
        stored.detailError = stored.thumbnailError = stored.candidateFtid = stored.candidateName = "";
        save(c);
    }

    /**
     * Maps showed a place (any place sheet): a saved place without a position -- from a
     * Takeout list -- takes Maps' own, and one known only by its customer id takes its
     * full feature id.
     */
    static synchronized void locate(Context c, String ftid, double lat, double lng) {
        if (ftid == null || !coordinates(lat, lng)) return;
        Place p = find(ftid);
        if (p == null || p.located() && ftid.equals(p.ftid)) return;
        places.remove(p.key());
        p.ftid = ftid;
        p.lat = lat;
        p.lng = lng;
        if (places.containsKey(p.key())) p.separateEntry = true;
        places.put(p.key(), p);
        save(c);
    }

    private static void importGeoJson(JSONObject root) throws Exception {
        JSONArray features = root.optJSONArray("features");
        if (features == null) throw new IOException("Unsupported JSON format; original file retained");
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject g = f.optJSONObject("geometry");
            JSONArray xy = g == null ? null : g.optJSONArray("coordinates");
            Place p = new Place();
            JSONObject props = f.optJSONObject("properties");
            String name = null;
            if (props != null) {
                JSONObject loc = props.optJSONObject("location");
                if (loc == null) loc = props.optJSONObject("Location");
                if (loc != null) name = firstOf(loc, "name", "Business Name", "address", "Address");
                if (name == null) name = firstOf(props, "name", "Title", "title");
                String link = firstOf(props, "Google Maps URL", "google_maps_url", "URL", "url");
                if (link != null) p = fromMapsLink(link, name);
            }
            if (g != null && "Point".equals(g.optString("type")) && xy != null && xy.length() >= 2 &&
                    coordinates(xy.optDouble(1, Double.NaN), xy.optDouble(0, Double.NaN))) {
                p.lng = p.sharedLng = xy.getDouble(0);
                p.lat = p.sharedLat = xy.getDouble(1);
            }
            p.name = name != null ? name : p.located() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : "Unresolved place";
            p.id = UUID.nameUUIDFromBytes(("geojson\n" + i + "\n" + f).getBytes(StandardCharsets.UTF_8)).toString();
            p.sources.add(new JSONObject().put("id", p.id).put("geojson", f));
            p.added = System.currentTimeMillis();
            merge(p);
        }
    }

    private static String firstOf(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    private static void importKml(Context c, String text) throws Exception {
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        x.setInput(new java.io.StringReader(text));
        String folder = null, name = null, coords = null, description = null, tag = null;
        int index = 0;
        boolean inPlacemark = false;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = x.getName();
                if (x.getDepth() == 1 && !"kml".equals(tag)) throw new IOException("Expected a KML document");
                if ("Placemark".equals(tag)) { inPlacemark = true; name = coords = description = null; }
            } else if (ev == XmlPullParser.TEXT && tag != null) {
                String v = x.getText().trim();
                if (v.isEmpty()) continue;
                if ("name".equals(tag)) { if (inPlacemark) name = v; else folder = v; }
                else if ("coordinates".equals(tag) && inPlacemark) coords = v;
                else if ("description".equals(tag) && inPlacemark) description = v;
            } else if (ev == XmlPullParser.END_TAG) {
                if ("Placemark".equals(x.getName())) {
                    inPlacemark = false;
                    Place p = new Place();
                    if (coords != null) {
                        String[] xy = coords.split("\\s+")[0].split(",");
                        try {
                            if (xy.length >= 2 && coordinates(Double.parseDouble(xy[1]), Double.parseDouble(xy[0]))) {
                                p.lng = p.sharedLng = Double.parseDouble(xy[0]);
                                p.lat = p.sharedLat = Double.parseDouble(xy[1]);
                            }
                        } catch (NumberFormatException e) { p.detailError = "Invalid KML coordinates"; }
                    }
                    p.name = name != null ? name : p.located() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : "Unresolved place";
                    p.added = System.currentTimeMillis();
                    String list = listForFolder(folder);
                    if (list != null) {
                        p.lists.add(list);
                        if (description != null) p.listNotes.put(list, description);
                        p.listEntries.put(list, ListTransfer.entry(p, list));
                    } else if (description != null) p.note = description;
                    p.id = UUID.nameUUIDFromBytes(("kml\n" + index++ + "\n" + folder + "\n" + name + "\n" + coords + "\n" + description)
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    p.sources.add(new JSONObject().put("id", p.id).put("folder", folder).put("name", name)
                            .put("coordinates", coords).put("description", description));
                    merge(p);
                }
                tag = null;
            }
        }
    }

    /** A KML folder becomes the list of the same name, created if needed; no folder, no list. */
    private static String listForFolder(String folder) {
        if (folder == null || folder.isEmpty() || "Ungoogled Maps saved places".equals(folder)
                || "Saved places".equals(folder) || "Home and Work".equals(folder) || "Labeled".equals(folder)
                || "Recent places".equals(folder)) return null;
        for (Map.Entry<String, String> e : lists.entrySet()) if (e.getValue().equalsIgnoreCase(folder)) return e.getKey();
        String id = UUID.randomUUID().toString();
        lists.put(id, folder);
        return id;
    }

    static String readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) {
                if (b.size() + n > MAX_FILE_BYTES) throw new IOException("Library file exceeds 64 MiB");
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }
}
