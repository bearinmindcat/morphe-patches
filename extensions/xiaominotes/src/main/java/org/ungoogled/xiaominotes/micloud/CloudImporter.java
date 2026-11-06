package org.ungoogled.xiaominotes.micloud;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies the notes of the signed-in Xiaomi Cloud account into Notes: lists everything the way the website does
 * (/note/full/page, 200 per page, or /note/v2/... for accounts in the new format), fetches each new or changed note,
 * converts it with Notes' own converter, saves its pictures and voice clips where Notes looks for them and inserts it
 * with its original dates and folder. Encrypted (end-to-end) and hidden notes cannot be read from the website and are
 * only counted. Re-runnable: State remembers what came from where.
 */
class CloudImporter {
    interface Listener {
        void progress(String text, int done, int total);
    }

    static final class Result {
        int imported, updated, conflicts, unchanged, folders, encrypted, hidden, unsupported, deletedHere, failedFiles;
        int uploaded, uploadedChanges, deletedInCloud, deletedFromCloud;

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Imported ").append(imported).append(imported == 1 ? " note, " : " notes, ")
                .append(folders).append(folders == 1 ? " folder" : " folders");
            if (encrypted > 0) sb.append("; ").append(encrypted).append(" encrypted ").append(encrypted == 1 ? "note" : "notes").append(" skipped");
            sb.append('.');
            List<String> more = new ArrayList<>();
            if (updated > 0) more.add(updated + " updated");
            if (conflicts > 0) more.add(conflicts + " changed on both sides (kept both, marked \"(conflict)\")");
            if (unchanged > 0) more.add(unchanged + " already up to date");
            if (hidden > 0) more.add(hidden + " hidden " + (hidden == 1 ? "note" : "notes") + " skipped");
            if (unsupported > 0) more.add(unsupported + " mind map or handwriting " + (unsupported == 1 ? "note" : "notes") + " skipped");
            if (deletedHere > 0) more.add(deletedHere + " deleted on this phone left out");
            if (failedFiles > 0) more.add(failedFiles + (failedFiles == 1 ? " picture or recording" : " pictures or recordings") + " could not be downloaded");
            if (!more.isEmpty()) sb.append("\n").append(join(more)).append('.');
            return sb.toString();
        }
    }

    static final String CONFLICT = " (conflict)";
    static final String EXTERNAL_PREFIX = "micloud:";
    /** The cloud's built-in folder ids (the website's names): 0 = no folder, 2 = hidden ("private") notes. */
    static final String NO_FOLDER = "0", HIDDEN_FOLDER = "2";
    private static final Map<String, String> SYSTEM_FOLDERS = new HashMap<>();

    static {
        SYSTEM_FOLDERS.put("1", "Call notes");
        SYSTEM_FOLDERS.put("3", "Audio notes");
        SYSTEM_FOLDERS.put("4", "Excerpts");
        SYSTEM_FOLDERS.put("5", "Blackboard");
        SYSTEM_FOLDERS.put("6", "Quick notes");
    }

    private static final Pattern IMAGE = Pattern.compile("https://com\\.miui\\.notes/image/([^?)\\s]+)");
    private static final Pattern AUDIO = Pattern.compile("https://com\\.miui\\.notes/audio/([^?)\\s]+)");
    private static final Pattern CARD = Pattern.compile("\\[[^\\]\\n]*]\\(https://com\\.miui\\.notes/external/([^/\\s)]+)/([^)\\s]+)\\)");
    private static final String[] IMAGE_EXTENSIONS = {"jpeg", "jpg", "png", "webp", "gif", "bmp"};

    final Context context;
    final MiCloudApi api;
    final State state;
    final NotesStore store;
    final Listener listener;
    final Result result = new Result();
    /** "" for the website's original format, "/v2" for accounts it shows in the new (Markdown) format. */
    String format = "";
    volatile boolean cancelled;
    /** Sync: a note deleted here but changed in the cloud comes back (the edit wins over the deletion). */
    boolean syncMode;

    /** One import or sync at a time. */
    private static final java.util.concurrent.atomic.AtomicReference<CloudImporter> ACTIVE = new java.util.concurrent.atomic.AtomicReference<>();

    static CloudImporter active() {
        return ACTIVE.get();
    }

    static boolean claim(CloudImporter job) {
        return ACTIVE.compareAndSet(null, job);
    }

    static void release(CloudImporter job) {
        ACTIVE.compareAndSet(job, null);
    }

    CloudImporter(Context context, MiCloudApi api, State state, NotesStore store, Listener listener) {
        this.context = context.getApplicationContext();
        this.api = api;
        this.state = state;
        this.store = store;
        this.listener = listener;
    }

    // ---- the run

    Result run() throws IOException {
        detectFormat();
        progress("Reading the list of notes…", 0, 0);
        Listing listing = listAll();
        MiCloudApi.trace("listed " + listing.entries.size() + " entries, " + listing.folders.size() + " folders @" + api.host);
        importFolders(listing.folders);
        List<JSONObject> notes = new ArrayList<>();
        boolean anyEncrypted = false;
        for (JSONObject e : listing.entries) {
            if (isNote(e)) notes.add(e);
            if (encrypted(e)) anyEncrypted = true;
        }
        // An end-to-end encrypted account: sync must never upload plain text to it.
        state.flag("e2ee", anyEncrypted);
        int done = 0;
        for (JSONObject e : notes) {
            if (cancelled) throw new IOException("cancelled");
            progress("Importing notes…", done, notes.size());
            String status = e.optString("status", "normal");
            if (!status.isEmpty() && !"normal".equals(status)) {
                done++;
                continue;
            }
            importNote(e);
            done++;
        }
        progress("Importing notes…", done, notes.size());
        if (listing.syncTag != null) state.set("syncTag", listing.syncTag);
        state.set("format", format);
        state.set("lastImport", String.valueOf(System.currentTimeMillis()));
        MiCloudApi.trace("import done: " + result.imported + " new, " + result.updated + " updated, " + result.unchanged
            + " unchanged, " + result.encrypted + " encrypted, " + result.hidden + " hidden, " + result.failedFiles + " files failed");
        return result;
    }

    void progress(String text, int done, int total) {
        if (listener != null) listener.progress(text, done, total);
    }

    /** The website asks /note/v2/hasData and, if true, uses the /note/v2/... calls. */
    void detectFormat() throws IOException {
        try {
            JSONObject data = api.get("/note/v2/hasData", null);
            format = data.optBoolean("hasData", false) ? "/v2" : "";
        } catch (MiCloudApi.SessionExpired | MiCloudApi.RegionMoved | MiCloudApi.Unreachable e) {
            throw e;
        } catch (IOException e) {
            // As the website: if the check fails, use the original format.
            MiCloudApi.trace("hasData failed (" + e.getMessage() + "), using the original format");
            format = "";
        }
        String previous = state.get("format");
        if (previous != null && !previous.equals(format)) {
            // The account moved to the new format: the notes have new ids there, start the record afresh.
            MiCloudApi.log("format changed " + previous + " -> " + format);
            state.set("syncTag", null);
        }
        MiCloudApi.trace("format " + (format.isEmpty() ? "v1" : "v2") + " @" + api.host);
    }

    String path(String rest) {
        return "/note" + format + rest;
    }

    static final class Listing {
        final List<JSONObject> entries = new ArrayList<>();
        final List<JSONObject> folders = new ArrayList<>();
        String syncTag;
    }

    /** Every page of /note/full/page, as the website loads it at start: entries, folders, the final syncTag. */
    Listing listAll() throws IOException {
        Listing listing = new Listing();
        Set<String> seenTags = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        String syncTag = null;
        for (int page = 0; page < 1000; page++) {
            if (cancelled) throw new IOException("cancelled");
            Map<String, String> params = MiCloudApi.map("limit", "200");
            if (syncTag != null) params.put("syncTag", syncTag);
            JSONObject data = api.get(path("/full/page"), params);
            JSONArray entries = data.optJSONArray("entries");
            JSONArray folders = data.optJSONArray("folders");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e != null && seenIds.add(str(e, "id"))) listing.entries.add(e);
                }
            }
            if (folders != null) for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f != null) listing.folders.add(f);
            }
            String next = data.has("syncTag") ? str(data, "syncTag") : null;
            if (next != null && !next.isEmpty()) listing.syncTag = next;
            progress("Reading the list of notes… " + listing.entries.size(), 0, 0);
            int added = entries == null ? 0 : entries.length();
            // The website pages until lastPage is true; without the field, a page that brought nothing new ends it.
            boolean last = data.has("lastPage") ? data.optBoolean("lastPage", true) : added == 0;
            if (last) return listing;
            if (next == null || next.isEmpty() || !seenTags.add(next)) {
                if (!data.has("lastPage")) return listing;
                MiCloudApi.trace("GET " + path("/full/page") + " @" + api.host + " did not page (lastPage false, syncTag " +
                    (next == null || next.isEmpty() ? "missing" : "repeated") + ")");
                throw new IOException("Xiaomi Cloud's note list did not page (" + api.host + ").");
            }
            syncTag = next;
        }
        throw new IOException("too many pages");
    }

    // ---- folders

    void importFolders(List<JSONObject> folders) throws IOException {
        for (JSONObject f : folders) {
            String status = f.optString("status", "normal");
            if (!status.isEmpty() && !"normal".equals(status)) continue;
            String id = str(f, "id");
            String name = f.optString("subject", "").trim();
            if (id.isEmpty() || name.isEmpty()) continue;
            localFolder(id, name, f.optLong("createDate", System.currentTimeMillis()), str(f, "tag"));
        }
    }

    /** The local folder for a cloud folder: the one used before, else a folder with the same name, else a new one. */
    Long localFolder(String cloudId, String name, long createdAt, String tag) throws IOException {
        if (cloudId == null || cloudId.isEmpty() || NO_FOLDER.equals(cloudId) || "-1".equals(cloudId)) return null;
        long known = state.folder(cloudId);
        if (known > 0 && store.folderName(known) != null) return known;
        if (name == null) name = SYSTEM_FOLDERS.get(cloudId);
        if (name == null || name.trim().isEmpty()) return null;
        name = name.trim();
        long id = -1;
        for (Object[] f : store.folders()) {
            if (name.equals(f[1])) {
                id = (Long) f[0];
                break;
            }
        }
        if (id <= 0) {
            try {
                id = store.addFolder(name, createdAt, System.currentTimeMillis());
            } catch (Exception e) {
                throw new IOException("could not create folder " + name, e);
            }
            if (id <= 0) throw new IOException("could not create folder " + name);
            result.folders++;
        }
        state.putFolder(cloudId, id, tag, name);
        return id;
    }

    // ---- notes

    boolean isNote(JSONObject e) {
        String type = e.optString("type", "");
        if (format.isEmpty()) return type.isEmpty() || "note".equals(type);
        return !"folder".equals(type);
    }

    static boolean encrypted(JSONObject e) {
        Object info = e.opt("encryptInfo");
        if (info == null || info == JSONObject.NULL) return false;
        if (info instanceof String) return !((String) info).trim().isEmpty();
        if (info instanceof JSONObject) return ((JSONObject) info).length() > 0;
        return true;
    }

    /** Imports or refreshes one listed cloud note (status normal). */
    void importNote(JSONObject listed) throws IOException {
        String id = str(listed, "id");
        if (id.isEmpty()) return;
        if (encrypted(listed)) {
            result.encrypted++;
            return;
        }
        String folder = str(listed, "folderId");
        if (HIDDEN_FOLDER.equals(folder)) {
            result.hidden++;
            return;
        }
        State.Note known = state.note(id);
        Object[] local = known != null ? store.note(known.localId) : null;
        if (known != null && local != null && str(listed, "tag").equals(known.tag)) {
            result.unchanged++;
            return;
        }
        if (!syncMode && local != null && local[NotesStore.DELETED] != null && known != null) {
            // Deleted on this phone (in Recently deleted): leave it there.
            known.tag = str(listed, "tag");
            known.modify = listed.optLong("modifyDate", known.modify);
            state.put(known);
            result.deletedHere++;
            return;
        }
        JSONObject entry = detail(id, listed);
        if (entry == null) return;
        if (encrypted(entry)) {
            result.encrypted++;
            return;
        }
        Content content = content(entry);
        if (content == null) {
            result.unsupported++;
            return;
        }
        writeLocal(entry, content, known, local, false);
    }

    /** The full note (/note/note/<id>/), merged over what the list said. */
    JSONObject detail(String id, JSONObject listed) throws IOException {
        pause(100);
        JSONObject data = api.get(path("/note/" + id + "/"), null);
        JSONObject entry = data.optJSONObject("entry");
        if (entry == null) throw new IOException("note " + id + " came back empty");
        JSONObject merged = new JSONObject();
        try {
            for (java.util.Iterator<String> it = listed.keys(); it.hasNext(); ) {
                String k = it.next();
                merged.put(k, listed.get(k));
            }
            for (java.util.Iterator<String> it = entry.keys(); it.hasNext(); ) {
                String k = it.next();
                merged.put(k, entry.get(k));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return merged;
    }

    static final class Content {
        String title;
        String markdown;
    }

    /** Title and Markdown for a cloud note, with its attachments downloaded; null for what Notes cannot show. */
    Content content(JSONObject entry) throws IOException {
        JSONObject extra = json(entry.opt("extraInfo"));
        Content c = new Content();
        c.title = extra.optString("title", "").trim();
        String body = entry.optString("content", "");
        if (format.isEmpty()) {
            if ("mind".equals(extra.optString("note_content_type"))) {
                String text = extra.optString("mind_content_plain_text", "").trim();
                if (text.isEmpty()) return null;
                c.markdown = Dao.toMarkdown(text);
            } else {
                c.markdown = Dao.toMarkdown(body);
            }
        } else {
            String type = entry.optString("type", "mdNote");
            if ("mindMap".equals(type)) {
                String text = extra.optString("mind_content_plain_text", "").trim();
                if (text.isEmpty()) return null;
                c.markdown = Dao.toMarkdown(text);
            } else if ("handNote".equals(type)) {
                return null;
            } else {
                c.markdown = expandCards(body, entry.opt("subNotes"));
            }
        }
        if (c.markdown == null) c.markdown = "";
        c.markdown = attachments(c.markdown, entry, extra);
        return c;
    }

    /**
     * New-format notes embed checklists and other cards as links to separate sub-notes; Notes keeps its cards as
     * separate entries of its own, so the card's items are put into the note instead (a checklist stays a checklist).
     */
    static String expandCards(String markdown, Object subNotes) {
        if (markdown == null) return "";
        Map<String, JSONObject> cards = new HashMap<>();
        JSONArray list = null;
        if (subNotes instanceof JSONArray) list = (JSONArray) subNotes;
        else if (subNotes instanceof String && !((String) subNotes).trim().isEmpty()) {
            try {
                list = new JSONArray((String) subNotes);
            } catch (JSONException ignored) {
            }
        }
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject card = list.optJSONObject(i);
            if (card != null) cards.put(str(card, "cardId"), card);
        }
        Matcher m = CARD.matcher(markdown);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            JSONObject card = cards.get(m.group(2));
            String replacement = "";
            if (card != null) {
                String type = card.optString("type", m.group(1));
                if ("checklist".equals(type)) {
                    replacement = card.optString("content", "").trim();
                } else if ("schedule".equals(type)) {
                    replacement = json(card.opt("extraInfo")).optString("title", "").trim();
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Downloads the note's pictures and voice clips into Notes' folders and points the Markdown at the saved files. */
    String attachments(String markdown, JSONObject entry, JSONObject extra) throws IOException {
        Map<String, String> mime = new HashMap<>();
        collectMime(mime, json(entry.opt("setting")).optJSONArray("data"));
        collectMime(mime, extra.optJSONArray("attachments"));
        Map<String, String> renamed = new HashMap<>();
        Matcher m = IMAGE.matcher(markdown);
        while (m.find()) {
            String id = m.group(1);
            if (renamed.containsKey(id)) continue;
            String name = saveImage(id, mime.get(id));
            renamed.put(id, name != null ? name : id);
        }
        StringBuffer out = new StringBuffer();
        m = IMAGE.matcher(markdown);
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("https://com.miui.notes/image/" + renamed.get(m.group(1))));
        m.appendTail(out);
        markdown = out.toString();

        Map<String, String> audio = new HashMap<>();
        m = AUDIO.matcher(markdown);
        while (m.find()) {
            String id = m.group(1);
            if (audio.containsKey(id)) continue;
            String name = safeName(id);
            File dest = new File(new File(context.getFilesDir(), "audio"), name);
            if (!(dest.isFile() && dest.length() > 0)) {
                try {
                    api.download(id, dest);
                } catch (MiCloudApi.SessionExpired e) {
                    throw e;
                } catch (IOException e) {
                    MiCloudApi.log("audio " + id + ": " + e.getMessage());
                    result.failedFiles++;
                }
            }
            audio.put(id, name);
        }
        out = new StringBuffer();
        m = AUDIO.matcher(markdown);
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("https://com.miui.notes/audio/" + audio.get(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    private static void collectMime(Map<String, String> mime, JSONArray list) {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null) continue;
            String id = str(a, "fileId");
            if (!id.isEmpty()) mime.put(id, a.optString("mimeType", ""));
        }
    }

    /**
     * Saves picture [id] as files/images/<id>.<ext>: Notes' editor only serves pictures whose name ends in an image
     * extension (cloud file ids have none). Returns the saved name, or null when it could not be downloaded.
     */
    String saveImage(String id, String mimeType) throws IOException {
        File dir = new File(context.getFilesDir(), "images");
        String safe = safeName(id);
        if (extension(safe) != null) {
            File dest = new File(dir, safe);
            if (dest.isFile() && dest.length() > 0) return safe;
            return download(id, dest) ? safe : null;
        }
        for (String ext : IMAGE_EXTENSIONS) {
            File f = new File(dir, safe + "." + ext);
            if (f.isFile() && f.length() > 0) return f.getName();
        }
        File tmp = new File(dir, safe + ".download");
        String type;
        try {
            type = api.download(id, tmp);
        } catch (MiCloudApi.SessionExpired e) {
            throw e;
        } catch (IOException e) {
            MiCloudApi.log("image " + id + ": " + e.getMessage());
            result.failedFiles++;
            tmp.delete();
            return null;
        }
        String ext = imageExtension(mimeType);
        if (ext == null) ext = imageExtension(type);
        if (ext == null) ext = sniff(tmp);
        File dest = new File(dir, safe + "." + ext);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            result.failedFiles++;
            return null;
        }
        return dest.getName();
    }

    private boolean download(String id, File dest) throws IOException {
        try {
            api.download(id, dest);
            return true;
        } catch (MiCloudApi.SessionExpired e) {
            throw e;
        } catch (IOException e) {
            MiCloudApi.log("file " + id + ": " + e.getMessage());
            result.failedFiles++;
            return false;
        }
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXTENSIONS) if (e.equals(ext)) return ext;
        return null;
    }

    static String imageExtension(String mimeType) {
        if (mimeType == null) return null;
        String m = mimeType.toLowerCase(Locale.ROOT);
        if (m.contains("png")) return "png";
        if (m.contains("gif")) return "gif";
        if (m.contains("webp")) return "webp";
        if (m.contains("bmp")) return "bmp";
        if (m.contains("jpeg") || m.contains("jpg")) return "jpeg";
        return null;
    }

    private static String sniff(File f) {
        byte[] head = new byte[12];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(head);
            if (n >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "png";
            if (n >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return "gif";
            if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W') return "webp";
            if (n >= 2 && head[0] == 'B' && head[1] == 'M') return "bmp";
        } catch (IOException ignored) {
        }
        return "jpeg";
    }

    /** A file name for a cloud file id: the id itself when it is a plain name, else a hash of it. */
    static String safeName(String id) {
        if (id.matches("[A-Za-z0-9._-]{1,120}") && !id.startsWith(".") && !id.contains("..")) return id;
        return "mc_" + State.hash(id, "").substring(0, 20);
    }

    /** Writes a cloud note into Notes: new, in place (not edited here since), or as a "(conflict)" copy. */
    long writeLocal(JSONObject entry, Content c, State.Note known, Object[] local, boolean asConflict) throws IOException {
        String id = str(entry, "id");
        String cloudFolder = str(entry, "folderId");
        Long folder = localFolder(cloudFolder, null, System.currentTimeMillis(), null);
        long created = entry.optLong("createDate", System.currentTimeMillis());
        long modified = entry.optLong("modifyDate", created);
        long sticky = json(entry.opt("setting")).optLong("stickyTime", 0L);
        String title = c.title;
        long localId;
        try {
            if (local != null && local[NotesStore.DELETED] == null && known != null
                && State.hash((String) local[NotesStore.TITLE], (String) local[NotesStore.CONTENT]).equals(known.hash)) {
                // Unchanged on this phone since it came from the cloud: take the cloud's version.
                local[NotesStore.TITLE] = title;
                local[NotesStore.CONTENT] = c.markdown;
                local[NotesStore.UPDATED] = modified;
                local[NotesStore.FOLDER] = folder;
                local[NotesStore.STICK] = sticky;
                localId = store.putNote(local);
                result.updated++;
            } else {
                boolean conflict = asConflict || (local != null && local[NotesStore.DELETED] == null);
                if (conflict) title = conflictTitle(title, c.markdown);
                Object[] v = NotesStore.newNote(title, c.markdown, created, modified);
                v[NotesStore.FOLDER] = folder;
                v[NotesStore.STICK] = sticky;
                v[NotesStore.EXTERNAL] = EXTERNAL_PREFIX + id;
                localId = store.putNote(v);
                if (!conflict) result.imported++;
                else if (!asConflict) result.conflicts++;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not save note: " + e, e);
        }
        if (localId <= 0) throw new IOException("Notes did not save the note");
        remember(entry, localId, title, c.markdown);
        return localId;
    }

    static String conflictTitle(String title, String markdown) {
        String base = title == null ? "" : title.trim();
        if (base.isEmpty()) {
            String preview = NotesStore.listPreview(markdown);
            int nl = preview.indexOf('\n');
            base = (nl >= 0 ? preview.substring(0, nl) : preview).trim();
            if (base.length() > 40) base = base.substring(0, 40);
        }
        return base + CONFLICT;
    }

    /** Records cloud note -> local note with what was written here. */
    void remember(JSONObject entry, long localId, String title, String markdown) {
        State.Note n = new State.Note();
        n.cloudId = str(entry, "id");
        n.localId = localId;
        n.tag = str(entry, "tag");
        n.modify = entry.optLong("modifyDate", 0L);
        n.hash = State.hash(title, markdown);
        n.cloudFolder = str(entry, "folderId");
        JSONObject meta = new JSONObject();
        try {
            for (java.util.Iterator<String> it = entry.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("content") && !k.equals("snippet") && !k.equals("subNotes")) meta.put(k, entry.get(k));
            }
        } catch (JSONException ignored) {
        }
        n.entry = meta.toString();
        state.put(n);
    }

    // ---- helpers

    static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Double || v instanceof Float) return new BigDecimal(((Number) v).doubleValue()).toPlainString();
        return String.valueOf(v);
    }

    static JSONObject json(Object v) {
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof String && !((String) v).trim().isEmpty()) {
            try {
                return new JSONObject((String) v);
            } catch (JSONException ignored) {
            }
        }
        return new JSONObject();
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(parts.get(i));
        }
        return sb.length() > 0 ? Character.toUpperCase(sb.charAt(0)) + sb.substring(1) : "";
    }
}
