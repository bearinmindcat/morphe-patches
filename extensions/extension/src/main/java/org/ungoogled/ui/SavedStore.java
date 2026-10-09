package org.ungoogled.ui;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Places saved without a Google account, modelled on Maps' own: a place is
 * saved (Save), may sit in any of the lists -- Want to go, Travel plans, Starred
 * places, Favorites and the user's own -- and may carry a note; Home, Work and
 * the user's own labels ("Labeled") sit alongside. One JSON file in the app's
 * private storage; nothing leaves the phone unless the user exports it.
 */
final class SavedStore {
    static final String FILE = "ungoogled_saved.json";
    static final String WANT_TO_GO = "want_to_go", TRAVEL = "travel_plans", STARRED = "starred", FAVOURITES = "favourites";
    /** The first version's catch-all list; its places are simply saved now. */
    private static final String OLD_SAVED = "saved";

    static final class Place {
        String ftid;            // Maps' feature id, "0x..:0x..", or null for a bare spot
        String name;
        String note = "";
        /** What Maps showed of the place, when known: its category line ("Real estate agency"), photo URLs, rating and review count. */
        String category = "";
        final List<String> photos = new ArrayList<>();
        float rating = Float.NaN;
        int reviews;

        String photo() { return photos.isEmpty() ? "" : photos.get(0); }

        /** Takes what [o] knows of the place and this does not. */
        void fillFrom(Place o) {
            if (o == null) return;
            if (category.isEmpty() && o.category != null) category = o.category;
            if (photos.isEmpty()) photos.addAll(o.photos);
            if (Float.isNaN(rating)) rating = o.rating;
            if (reviews == 0) reviews = o.reviews;
        }
        double lat, lng;
        long added;
        final Set<String> lists = new LinkedHashSet<>();

        /** Has a position: a place from a Google Takeout list has none until Maps shows it. */
        boolean located() {
            return lat != 0 || lng != 0;
        }

        /** The feature id when there is one, else the spot itself. */
        String key() {
            return ftid != null ? ftid : String.format(Locale.US, "%.6f,%.6f", lat, lng);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            if (ftid != null) o.put("ftid", ftid);
            o.put("name", name);
            if (!note.isEmpty()) o.put("note", note);
            if (!category.isEmpty()) o.put("category", category);
            if (!photos.isEmpty()) o.put("photos", new JSONArray(photos));
            if (!Float.isNaN(rating)) o.put("rating", (double) rating);
            if (reviews > 0) o.put("reviews", reviews);
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("added", added);
            o.put("lists", new JSONArray(lists));
            return o;
        }

        static Place fromJson(JSONObject o) {
            Place p = new Place();
            p.ftid = o.has("ftid") ? o.optString("ftid", null) : null;
            p.name = o.optString("name", "");
            p.note = o.optString("note", "");
            p.category = o.optString("category", "");
            readPhotos(o, p.photos);
            p.rating = (float) o.optDouble("rating", Double.NaN);
            p.reviews = o.optInt("reviews", 0);
            p.lat = o.optDouble("lat");
            p.lng = o.optDouble("lng");
            p.added = o.optLong("added", System.currentTimeMillis());
            JSONArray l = o.optJSONArray("lists");
            if (l != null) for (int i = 0; i < l.length(); i++) p.lists.add(l.optString(i));
            p.lists.remove(OLD_SAVED);
            return p;
        }
    }

    /** "photos": [...], or a first version's single "photo". */
    static void readPhotos(JSONObject o, List<String> into) {
        JSONArray a = o.optJSONArray("photos");
        if (a != null) for (int i = 0; i < a.length(); i++) { String u = a.optString(i, ""); if (!u.isEmpty()) into.add(u); }
        else if (!o.optString("photo", "").isEmpty()) into.add(o.optString("photo"));
    }

    private static boolean loaded;
    /** List id -> name, in display order. */
    static final Map<String, String> lists = new LinkedHashMap<>();
    /** Key -> place, newest last. */
    static final Map<String, Place> places = new LinkedHashMap<>();
    static Place home, work;
    /** The user's own labels ("Gym", "Mum's"), label -> place: Maps' "Labeled" beside Home and Work. */
    static final Map<String, Place> labels = new LinkedHashMap<>();
    /** Lists whose places are not drawn on the map ("Hide on map"); every other list is. */
    static final Set<String> hiddenOnMap = new LinkedHashSet<>();

    private SavedStore() {}

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        lists.clear();
        places.clear();
        labels.clear();
        hiddenOnMap.clear();
        home = work = null;
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) read(new JSONObject(readAll(new java.io.FileInputStream(f))), true);
        } catch (Throwable ignored) {}
        ensureDefaultLists();
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
        JSONArray l = root.optJSONArray("lists");
        if (l != null) for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.getJSONObject(i);
            if (replace || !lists.containsKey(o.getString("id"))) lists.put(o.getString("id"), o.getString("name"));
        }
        JSONArray ps = root.optJSONArray("places");
        if (ps != null) for (int i = 0; i < ps.length(); i++) merge(Place.fromJson(ps.getJSONObject(i)));
        if (root.has("home") && (replace || home == null)) home = Place.fromJson(root.getJSONObject("home"));
        if (root.has("work") && (replace || work == null)) work = Place.fromJson(root.getJSONObject("work"));
        JSONArray lb = root.optJSONArray("labels");
        if (lb != null) for (int i = 0; i < lb.length(); i++) {
            JSONObject o = lb.getJSONObject(i);
            String label = o.optString("label", "");
            JSONObject at = o.optJSONObject("place");
            if (!label.isEmpty() && at != null && (replace || !labels.containsKey(label))) labels.put(label, Place.fromJson(at));
        }
        JSONArray hidden = root.optJSONArray("hiddenOnMap");
        if (hidden != null) for (int i = 0; i < hidden.length(); i++) hiddenOnMap.add(hidden.optString(i));
    }

    private static void merge(Place p) {
        Place old = places.get(p.key());
        if (old == null) places.put(p.key(), p);
        else old.lists.addAll(p.lists);
    }

    static synchronized void save(Context c) {
        try {
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(toJson().toString(1).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) tmp.delete();
        } catch (Throwable ignored) {}
        SavedOnMap.refresh();
    }

    static synchronized JSONObject toJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Ungoogled Maps");
        root.put("version", 2);
        JSONArray l = new JSONArray();
        for (Map.Entry<String, String> e : lists.entrySet()) l.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue()));
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

    // ---- queries and edits ------------------------------------------------------

    static synchronized Place find(String key) {
        return key == null ? null : places.get(key);
    }

    static synchronized List<Place> inList(String listId) {
        List<Place> out = new ArrayList<>();
        for (Place p : places.values()) if (p.lists.contains(listId)) out.add(p);
        java.util.Collections.reverse(out);   // newest first
        return out;
    }

    /** Save: keeps [p] (in no list yet) unless it is already kept. Returns the stored place. */
    static synchronized Place keep(Context c, Place p) {
        Place stored = places.get(p.key());
        if (stored != null) return stored;
        p.added = System.currentTimeMillis();
        places.put(p.key(), p);
        save(c);
        return p;
    }

    /** Unsave: forgets [p] and its lists and note. */
    static synchronized void unsave(Context c, Place p) {
        places.remove(p.key());
        save(c);
    }

    /** Puts a saved place in exactly [listIds] (none is fine: it stays saved). */
    static synchronized void setLists(Context c, Place p, Set<String> listIds) {
        Place stored = keep(c, p);
        stored.lists.clear();
        stored.lists.addAll(listIds);
        save(c);
    }

    static synchronized void setNote(Context c, Place p, String note) {
        Place stored = places.get(p.key());
        if (stored == null) return;
        stored.note = note == null ? "" : note.trim();
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
        java.util.Collections.reverse(out);
        return out;
    }

    /** Saved places that are in no list, newest first. */
    static synchronized List<Place> unlisted() {
        List<Place> out = new ArrayList<>();
        for (Place p : places.values()) if (p.lists.isEmpty()) out.add(p);
        java.util.Collections.reverse(out);
        return out;
    }

    static synchronized String addList(Context c, String name) {
        String id = "l" + System.currentTimeMillis();
        lists.put(id, name);
        save(c);
        return id;
    }

    static synchronized void renameList(Context c, String id, String name) {
        if (lists.containsKey(id)) lists.put(id, name);
        save(c);
    }

    /** Deletes a list of the user's own; its places stay saved. */
    static synchronized void deleteList(Context c, String id) {
        if (isDefault(id)) return;
        lists.remove(id);
        for (Place p : places.values()) p.lists.remove(id);
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
            Place old = places.get(p.key());
            if (old == null) {
                places.put(p.key(), p);
                added++;
                continue;
            }
            old.lists.addAll(p.lists);
            if (old.note.isEmpty()) old.note = p.note;
            if (old.lat == 0 && old.lng == 0) { old.lat = p.lat; old.lng = p.lng; }
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
        c.ftid = p.ftid; c.name = p.name; c.lat = p.lat; c.lng = p.lng; c.added = System.currentTimeMillis();
        c.fillFrom(p);
        return c;
    }

    static boolean same(Place a, Place b) {
        return a != null && b != null && a.key().equals(b.key());
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

    private static void write(Context c, Uri to, String text) throws Exception {
        try (OutputStream out = c.getContentResolver().openOutputStream(to, "wt")) {
            if (out == null) throw new java.io.IOException("cannot write " + to);
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** What an import added: places, new lists, places still without a position, and files read. */
    static final class Imported {
        int places, lists, unlocated, files;
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
        }
        save(c);
        return r;
    }

    /** One file's text, by what it holds; [name] is its file name, which names a Takeout list. */
    private static void importText(Context c, String text, String name) throws Exception {
        String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
        t = t.trim();
        synchronized (SavedStore.class) {
            if (t.startsWith("{")) {
                JSONObject root = new JSONObject(t);
                if (root.has("places") || root.has("lists")) read(root, false);
                else importGeoJson(root);
                JSONArray history = root.optJSONArray("history");
                if (history != null) HistoryStore.merge(c, history);
            } else if (t.startsWith("<")) {
                importKml(c, t);
            } else {
                importCsv(t, name);
            }
        }
    }

    /** A Takeout .zip: every saved list (Takeout/Saved/<list>.csv) and the starred places (Saved Places.json). */
    private static void importZip(Context c, InputStream in, Imported r) throws Exception {
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in);
        for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
            if (e.isDirectory()) continue;
            String path = e.getName().replace('\\', '/');
            String file = path.substring(path.lastIndexOf('/') + 1);
            String lower = ("/" + path).toLowerCase(Locale.ROOT);
            boolean list = lower.endsWith(".csv") && lower.contains("/saved/");
            boolean starred = file.equalsIgnoreCase("Saved Places.json");
            if (!list && !starred) continue;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = zip.read(buf)) > 0; ) b.write(buf, 0, n);
            importText(c, b.toString("UTF-8"), file);
            r.files++;
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (android.database.Cursor q = c.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    // ---- Google Takeout's saved lists ------------------------------------------------------

    private static final java.util.regex.Pattern FEATURE_ID = java.util.regex.Pattern.compile("!1s(0x[0-9a-fA-F]+):(0x[0-9a-fA-F]+)");
    private static final java.util.regex.Pattern CID = java.util.regex.Pattern.compile("[?&]cid=(\\d+)");
    private static final java.util.regex.Pattern PIN = java.util.regex.Pattern.compile("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)");
    private static final java.util.regex.Pattern SPOT = java.util.regex.Pattern.compile(
            "(?:/search/|/place/|[?&](?:q|query|ll|destination)=|@)\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*\\+?(-?\\d{1,3}(?:\\.\\d+)?)");

    /**
     * One saved list from Google Takeout: a CSV (Title, Note, URL, Tags, Comment) whose links
     * name each place by Maps' feature id -- no position, which the place takes from Maps the
     * first time Maps shows it -- or, for a dropped pin, by its coordinates.
     */
    private static void importCsv(String text, String fileName) {
        List<List<String>> rows = csv(text);
        if (rows.isEmpty()) return;
        List<String> header = rows.get(0);
        int title = column(header, 0, "title"), note = column(header, 1, "note"), url = column(header, 2, "url"),
                comment = column(header, 4, "comment");
        String list = listForCsv(fileName);
        long now = System.currentTimeMillis();
        // The list's first row on top here too: the store keeps its newest last.
        for (int i = rows.size() - 1; i >= 1; i--) {
            List<String> row = rows.get(i);
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
            if (p == null) continue;
            String n = cell(row, note), m = cell(row, comment);
            p.note = n.isEmpty() ? m : m.isEmpty() || m.equals(n) ? n : n + "\n" + m;
            if (list != null) p.lists.add(list);
            p.added = now - i;
            Place old = places.get(p.key());
            if (old == null && p.ftid != null && p.ftid.startsWith("0x0:")) {
                // Known only by its customer id: the place saved under its full feature id, if there is one.
                String cid = p.ftid.substring(3);
                for (Place q : places.values()) if (q.ftid != null && q.ftid.endsWith(cid)) { old = q; break; }
            }
            if (old == null) {
                places.put(p.key(), p);
                continue;
            }
            old.lists.addAll(p.lists);
            if (old.note.isEmpty()) old.note = p.note;
            if (!old.located() && p.located()) { old.lat = p.lat; old.lng = p.lng; }
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
        String link = Uri.decode(url == null ? "" : url);
        java.util.regex.Matcher m = FEATURE_ID.matcher(link);
        if (m.find()) {
            try {
                p.ftid = "0x" + Long.toHexString(Long.parseUnsignedLong(m.group(1).substring(2), 16))
                        + ":0x" + Long.toHexString(Long.parseUnsignedLong(m.group(2).substring(2), 16));
            } catch (Throwable ignored) {}
        }
        if (p.ftid == null && (m = CID.matcher(link)).find()) {
            try {
                p.ftid = "0x0:0x" + Long.toHexString(Long.parseUnsignedLong(m.group(1)));
            } catch (Throwable ignored) {}
        }
        // A position only where the link really names the spot: a place's own (!3d!4d), or a dropped pin's.
        m = PIN.matcher(link);
        boolean spot = m.find();
        if (!spot && p.ftid == null) {
            m = SPOT.matcher(link);
            spot = m.find();
        }
        if (spot) {
            try {
                double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
                if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) { p.lat = lat; p.lng = lng; }
            } catch (Throwable ignored) {}
        }
        if (p.ftid == null && !p.located()) return null;
        String name = title == null ? "" : title.trim();
        if (name.isEmpty()) {
            int at = link.indexOf("/place/");
            if (at >= 0) {
                String rest = link.substring(at + 7);
                int end = rest.indexOf('/');
                name = (end >= 0 ? rest.substring(0, end) : rest).replace('+', ' ').trim();
            }
        }
        p.name = name.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : name;
        return p;
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
        for (Map.Entry<String, String> e : lists.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        String id = "l" + System.nanoTime();
        lists.put(id, name);
        return id;
    }

    /** RFC 4180: quoted fields may hold commas, line breaks and doubled quotes. */
    static List<List<String>> csv(String s) {
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
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Maps showed [p] in detail: the saved place of the same key takes what it lacks, its position too. */
    static synchronized void refresh(Context c, Place p) {
        Place stored = places.get(p.key());
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

    /**
     * Maps showed a place (any place sheet): a saved place without a position -- from a
     * Takeout list -- takes Maps' own, and one known only by its customer id takes its
     * full feature id.
     */
    static synchronized void locate(Context c, String ftid, double lat, double lng) {
        if (ftid == null || (lat == 0 && lng == 0)) return;
        Place p = places.get(ftid);
        if (p == null) {
            int colon = ftid.indexOf(':');
            if (colon < 0) return;
            String byCid = "0x0" + ftid.substring(colon);
            p = places.get(byCid);
            if (p == null) return;
            // Re-keyed under its full feature id, in place.
            Map<String, Place> copy = new LinkedHashMap<>(places);
            places.clear();
            for (Map.Entry<String, Place> e : copy.entrySet()) places.put(e.getKey().equals(byCid) ? ftid : e.getKey(), e.getValue());
            p.ftid = ftid;
        } else if (p.located()) {
            return;
        }
        p.lat = lat;
        p.lng = lng;
        save(c);
    }

    private static void importGeoJson(JSONObject root) throws Exception {
        JSONArray features = root.optJSONArray("features");
        if (features == null) return;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject g = f.optJSONObject("geometry");
            JSONArray xy = g == null ? null : g.optJSONArray("coordinates");
            if (xy == null || xy.length() < 2) continue;
            Place p = new Place();
            p.lng = xy.getDouble(0);
            p.lat = xy.getDouble(1);
            if (p.lat == 0 && p.lng == 0) continue;
            JSONObject props = f.optJSONObject("properties");
            String name = null;
            if (props != null) {
                JSONObject loc = props.optJSONObject("location");
                if (loc == null) loc = props.optJSONObject("Location");
                if (loc != null) name = firstOf(loc, "name", "Business Name", "address", "Address");
                if (name == null) name = firstOf(props, "name", "Title", "title");
            }
            p.name = name != null ? name : String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng);
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
        x.setInput(new java.io.StringReader(text));
        String folder = null, name = null, coords = null, tag = null;
        boolean inPlacemark = false;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = x.getName();
                if ("Placemark".equals(tag)) { inPlacemark = true; name = coords = null; }
            } else if (ev == XmlPullParser.TEXT && tag != null) {
                String v = x.getText().trim();
                if (v.isEmpty()) continue;
                if ("name".equals(tag)) { if (inPlacemark) name = v; else folder = v; }
                else if ("coordinates".equals(tag) && inPlacemark) coords = v;
            } else if (ev == XmlPullParser.END_TAG) {
                if ("Placemark".equals(x.getName())) {
                    inPlacemark = false;
                    if (coords != null) {
                        String[] xy = coords.split("\\s+")[0].split(",");
                        Place p = new Place();
                        p.lng = Double.parseDouble(xy[0]);
                        p.lat = Double.parseDouble(xy[1]);
                        p.name = name != null ? name : String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng);
                        p.added = System.currentTimeMillis();
                        String list = listForFolder(folder);
                        if (list != null) p.lists.add(list);
                        merge(p);
                    }
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
        String id = "l" + System.nanoTime();
        lists.put(id, folder);
        return id;
    }

    static String readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
