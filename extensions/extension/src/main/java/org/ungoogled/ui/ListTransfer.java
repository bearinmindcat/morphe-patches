package org.ungoogled.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A static, allowlisted projection of one list, not a backup or a synchronization protocol. */
final class ListTransfer {
    static final int EMOJI_LIMIT = 64;
    // Keep the existing portable file format readable without adopting its product branding.
    private static final String FORMAT = "trailmark-list";

    private ListTransfer() {}

    static JSONObject entry(SavedStore.Place p, String list) throws Exception {
        if (!p.lists.contains(list)) throw new IllegalArgumentException("Place is not in this list");
        JSONObject existing = p.listEntries.get(list);
        JSONObject value = existing == null ? new JSONObject().put("type", "place")
                .put("title", p.name).put("sourceUrl", reference(p))
                .put("lat", SavedStore.coordinates(p.sharedLat, p.sharedLng) ? p.sharedLat : JSONObject.NULL)
                .put("lng", SavedStore.coordinates(p.sharedLat, p.sharedLng) ? p.sharedLng : JSONObject.NULL)
                : new JSONObject(existing.toString());
        value.put("sharedNote", p.listNote(list));
        return value;
    }

    static JSONObject document(String list) throws Exception {
        synchronized (SavedStore.class) {
            if (!SavedStore.lists.containsKey(list)) throw new IllegalArgumentException("List no longer exists");
            JSONObject metadata = new JSONObject().put("type", "list").put("title", SavedStore.lists.get(list))
                    .put("emoji", SavedStore.emojis.getOrDefault(list, ""))
                    .put("description", SavedStore.descriptions.getOrDefault(list, ""));
            validate(metadata);
            JSONArray places = new JSONArray();
            Set<String> ids = new HashSet<>();
            for (SavedStore.Place p : SavedStore.inList(list)) {
                JSONObject value = entry(p, list);
                validate(value);
                value.put("id", p.membershipId(list));
                String id = text(value, "id", 128, false);
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate list entry identifier");
                places.put(value);
            }
            return new JSONObject().put("format", FORMAT).put("version", 1).put("list", metadata).put("places", places);
        }
    }

    /** Each import is an independent local copy, even if a list with the same title exists. */
    static String importDocument(JSONObject document) throws Exception {
        fields(document, "format", "version", "list", "places");
        if (!FORMAT.equals(document.getString("format")) || !(document.get("version") instanceof Number) ||
                document.getDouble("version") != 1)
            throw new IllegalArgumentException("Unsupported list snapshot format or version");
        JSONObject metadata = document.getJSONObject("list");
        validate(metadata);
        if (!"list".equals(metadata.getString("type"))) throw new IllegalArgumentException("Missing list metadata");
        JSONArray entries = document.getJSONArray("places");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject raw = entries.getJSONObject(i);
            String id = text(raw, "id", 128, false);
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate snapshot entry identifier");
            JSONObject value = new JSONObject(raw.toString());
            value.remove("id");
            validate(value);
            if (!"place".equals(value.getString("type"))) throw new IllegalArgumentException("Invalid snapshot place");
        }
        synchronized (SavedStore.class) {
            String list = UUID.randomUUID().toString();
            SavedStore.lists.put(list, metadata.getString("title"));
            SavedStore.emojis.put(list, metadata.getString("emoji"));
            SavedStore.descriptions.put(list, metadata.getString("description"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject raw = entries.getJSONObject(i);
                JSONObject value = new JSONObject(raw.toString());
                String id = value.getString("id");
                value.remove("id");
                SavedStore.Place p = SavedStore.fromMapsLink(value.getString("sourceUrl"), value.getString("title"));
                if (!value.isNull("lat")) {
                    p.lat = p.sharedLat = value.getDouble("lat");
                    p.lng = p.sharedLng = value.getDouble("lng");
                }
                p.separateEntry = true;
                p.added = now - i;
                p.lists.add(list);
                p.membershipIds.put(list, id);
                p.listEntries.put(list, value);
                p.listNotes.put(list, value.getString("sharedNote"));
                p.sources.add(new JSONObject().put("id", id).put("snapshot", new JSONObject(raw.toString())));
                SavedStore.places.put(p.key(), p);
            }
            return list;
        }
    }

    static void validate(JSONObject value) throws Exception {
        String type = value.getString("type");
        if ("list".equals(type)) {
            fields(value, "type", "title", "emoji", "description");
            text(value, "title", 200, false);
            text(value, "description", 2000, true);
            String emoji = text(value, "emoji", EMOJI_LIMIT, true);
            if (!emoji.isEmpty()) {
                for (int i = 0; i < emoji.length(); i++) if (emoji.charAt(i) < 32)
                    throw new IllegalArgumentException("List icon cannot contain control characters");
                android.icu.text.BreakIterator boundary = android.icu.text.BreakIterator.getCharacterInstance();
                boundary.setText(emoji);
                if (boundary.next() != emoji.length()) throw new IllegalArgumentException("List icon must be one complete character");
            }
        } else if ("place".equals(type)) {
            fields(value, "type", "title", "sharedNote", "sourceUrl", "lat", "lng");
            text(value, "title", 300, false);
            text(value, "sharedNote", 2000, true);
            String link = text(value, "sourceUrl", 2048, true);
            if (!link.isEmpty()) {
                URI uri = new URI(link);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("Snapshot links must be HTTPS without credentials");
                for (String parameter : android.net.Uri.parse(link).getQueryParameterNames()) {
                    if (parameter.toLowerCase(java.util.Locale.ROOT).matches(
                            "(?:key|api_?key|x-goog-api-key|access_token|refresh_token|token|auth|authorization|password|signature)"))
                        throw new IllegalArgumentException("Remove credentials from the selected list's source link before sharing");
                }
            }
            if (value.isNull("lat") != value.isNull("lng"))
                throw new IllegalArgumentException("Snapshot coordinates must be a complete pair or both unknown");
            if (!value.isNull("lat") && (!(value.get("lat") instanceof Number) || !(value.get("lng") instanceof Number)
                    || !SavedStore.coordinates(value.getDouble("lat"), value.getDouble("lng"))))
                throw new IllegalArgumentException("Invalid snapshot coordinates");
        } else throw new IllegalArgumentException("Unsupported snapshot record type");
    }

    private static void fields(JSONObject value, String... names) throws Exception {
        if (value.length() != names.length) throw new IllegalArgumentException("Unexpected snapshot fields");
        for (String name : names) if (!value.has(name)) throw new IllegalArgumentException("Missing snapshot field: " + name);
    }

    private static String text(JSONObject value, String key, int max, boolean empty) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException("Invalid text field: " + key);
        String text = (String) raw;
        if (text.length() > max || !empty && text.trim().isEmpty()) throw new IllegalArgumentException("Invalid length: " + key);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch < 32 && ch != '\n' && ch != '\t' && ch != '\r') || ch == 127)
                throw new IllegalArgumentException("Unsupported control character");
        }
        return text;
    }

    private static String reference(SavedStore.Place p) {
        if (!p.sourceUrl.isEmpty() && !p.sourceUrl.startsWith("geo:")) return p.sourceUrl;
        if (SavedStore.coordinates(p.sharedLat, p.sharedLng)) return "https://www.google.com/maps/search/?api=1&query="
                + android.net.Uri.encode(p.sharedLat + "," + p.sharedLng);
        String link = SavedPlaces.placeUrl(p);
        return link.startsWith("https:") ? link : "";
    }
}
