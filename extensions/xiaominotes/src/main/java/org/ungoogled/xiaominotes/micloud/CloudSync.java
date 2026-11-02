package org.ungoogled.xiaominotes.micloud;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Experimental two-way sync with Xiaomi Cloud through the website's own calls (off by default). One run:
 * pull (the website's /note/sync/full/ changes since the last syncTag; the first run lists everything like the import),
 * then push (POST /note/note/<id> with the tag last seen, /note/note for notes made here, /note/full/<id>/delete for
 * synced notes deleted here, /note/folder for new folders).
 * Never overwrites silently: the cloud refuses an update whose tag is stale, and whenever both sides changed a note both
 * versions are kept (the cloud's comes down as "<title> (conflict)", ours goes up as a new note). Deletions only travel
 * for notes that were synced before; one side deleting while the other edited keeps the edit. Hidden (private) and
 * end-to-end encrypted notes are never touched, and nothing is uploaded to an end-to-end encrypted account.
 * Runs when asked ("Sync now") and when Notes' note list opens (at most every few minutes); there is no background job.
 */
public final class CloudSync extends CloudImporter {
    private static final long APP_OPEN_INTERVAL = 5 * 60_000L;
    private static final long CHECK_INTERVAL = 30_000L;
    private static volatile long lastAppOpenRun;
    private static volatile long lastCheck;
    private static volatile boolean warnedSignIn;

    private final CloudFormat.Report report = new CloudFormat.Report();
    private boolean e2ee;

    CloudSync(Context context, MiCloudApi api, State state, NotesStore store, Listener listener) {
        super(context, api, state, store, listener);
        syncMode = true;
    }

    // ---- the run

    Result sync() throws IOException {
        detectFormat();
        String tag = state.get("syncTag");
        e2ee = state.flag("e2ee");
        if (tag == null) {
            progress("Reading the list of notes…", 0, 0);
            Listing listing = listAll();
            e2ee = false;
            importFolders(listing.folders);
            int done = 0;
            for (JSONObject e : listing.entries) {
                if (cancelled) throw new IOException("cancelled");
                if (encrypted(e)) e2ee = true;
                if (isNote(e) && isNormal(e)) importNote(e);
                progress("Syncing…", ++done, listing.entries.size());
            }
            tag = listing.syncTag;
        } else {
            tag = pull(tag);
        }
        if (tag != null) state.set("syncTag", tag);
        state.set("format", format);
        state.flag("e2ee", e2ee);
        if (e2ee) {
            MiCloudApi.log("end-to-end encrypted account: not uploading");
        } else {
            push();
        }
        state.set("lastSync", String.valueOf(System.currentTimeMillis()));
        state.set("needsSignIn", null);
        return result;
    }

    static boolean isNormal(JSONObject e) {
        String status = e.optString("status", "normal");
        return status.isEmpty() || "normal".equals(status);
    }

    /** The website's incremental call: everything changed since [tag], as long as it says there is more. */
    private String pull(String tag) throws IOException {
        for (int round = 0; round < 50; round++) {
            if (cancelled) throw new IOException("cancelled");
            progress("Checking Xiaomi Cloud for changes…", 0, 0);
            JSONObject view = new JSONObject();
            try {
                view.put("note_view", new JSONObject().put("syncTag", tag));
            } catch (JSONException e) {
                throw new IOException(e);
            }
            JSONObject data = api.get(path("/sync/full/"), MiCloudApi.map("data", view.toString(), "inactiveTime", "10"));
            JSONObject noteView = data.optJSONObject("note_view");
            if (noteView == null) throw new IOException("sync answer without note_view");
            if (noteView.optInt("code", 0) != 0) throw new IOException("sync error " + noteView.optInt("code"));
            JSONObject changes = noteView.optJSONObject("data");
            if (changes == null) break;
            JSONArray folders = changes.optJSONArray("folders");
            if (folders != null) {
                java.util.List<JSONObject> list = new java.util.ArrayList<>();
                for (int i = 0; i < folders.length(); i++) if (folders.optJSONObject(i) != null) list.add(folders.optJSONObject(i));
                importFolders(list);
            }
            JSONArray entries = changes.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null || !isNote(e)) continue;
                    if (encrypted(e) && isNormal(e)) e2ee = true;
                    if (isNormal(e)) importNote(e);
                    else deletedInCloud(e);
                    progress("Syncing…", i + 1, entries.length());
                }
            }
            String next = str(changes, "syncTag");
            if (!next.isEmpty()) tag = next;
            if (!changes.optBoolean("haveExtra", false)) break;
        }
        return tag;
    }

    /** A synced note deleted in the cloud goes to Recently deleted here, unless it was edited here since. */
    private void deletedInCloud(JSONObject e) throws IOException {
        State.Note known = state.note(str(e, "id"));
        if (known == null) return;
        Object[] local = store.note(known.localId);
        if (local == null || local[NotesStore.DELETED] != null) {
            state.removeNote(known.cloudId);
            return;
        }
        if (State.hash((String) local[NotesStore.TITLE], (String) local[NotesStore.CONTENT]).equals(known.hash)) {
            local[NotesStore.DELETED] = System.currentTimeMillis();
            try {
                store.putNote(local);
            } catch (Exception ex) {
                throw new IOException("could not delete note: " + ex, ex);
            }
            result.deletedFromCloud++;
        } else {
            // Edited here, deleted there: keep the edit; it goes up again as a new note.
            result.conflicts++;
        }
        state.removeNote(known.cloudId);
    }

    // ---- push

    private int refused;

    private interface Step {
        void run() throws IOException;
    }

    /** One note's upload; the cloud refusing it (an error code) skips that note, a lost session stops the run. */
    private void attempt(Step step) throws IOException {
        try {
            step.run();
        } catch (MiCloudApi.ApiError e) {
            Log.w(MiCloudApi.TAG, "upload refused: " + e.getMessage());
            refused++;
        }
    }

    private void push() throws IOException {
        progress("Sending changes to Xiaomi Cloud…", 0, 0);
        for (State.Note n : state.notes()) {
            if (cancelled) throw new IOException("cancelled");
            Object[] local = store.note(n.localId);
            if (local == null || local[NotesStore.DELETED] != null) {
                attempt(() -> deleteInCloud(n));
                continue;
            }
            if (Boolean.TRUE.equals(local[NotesStore.PRIVATE])) continue;
            String hash = State.hash((String) local[NotesStore.TITLE], (String) local[NotesStore.CONTENT]);
            if (hash.equals(n.hash)) continue;
            if (special(json(n.entry))) {
                // A mind map or handwriting note came down as text; the text goes up as a note of its own instead of
                // replacing the original.
                state.removeNote(n.cloudId);
                attempt(() -> createInCloud(local));
                continue;
            }
            attempt(() -> updateInCloud(n, local, hash));
        }
        Set<Long> mapped = new HashSet<>();
        for (State.Note n : state.notes()) mapped.add(n.localId);
        for (long[] row : store.plainNotes()) {
            if (cancelled) throw new IOException("cancelled");
            if (row[2] != 0 || mapped.contains(row[0])) continue;
            Object[] local = store.note(row[0]);
            if (local == null || blank(local[NotesStore.TITLE]) && blank(local[NotesStore.CONTENT])) continue;
            attempt(() -> createInCloud(local));
        }
    }

    private static boolean blank(Object s) {
        return s == null || s.toString().trim().isEmpty();
    }

    /** Cloud notes that are not text: a mind map or handwriting. */
    private static boolean special(JSONObject entry) {
        String type = entry.optString("type", "");
        return "mind".equals(json(entry.opt("extraInfo")).optString("note_content_type")) || "mindMap".equals(type)
            || "handNote".equals(type);
    }

    /** Pictures and recordings the cloud knows for this note (by file id). */
    private static Set<String> cloudFiles(JSONObject entry) {
        Set<String> ids = new HashSet<>();
        JSONArray data = json(entry.opt("setting")).optJSONArray("data");
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            if (a != null && !str(a, "fileId").isEmpty()) ids.add(str(a, "fileId"));
        }
        JSONArray more = json(entry.opt("extraInfo")).optJSONArray("attachments");
        if (more != null) for (int i = 0; i < more.length(); i++) {
            JSONObject a = more.optJSONObject(i);
            if (a != null && !str(a, "fileId").isEmpty()) ids.add(str(a, "fileId"));
        }
        return ids;
    }

    private String cloudContent(String markdown, Set<String> files) {
        return format.isEmpty() ? CloudFormat.toMiui(markdown, files, report) : CloudFormat.toMarkdown(markdown, files, report);
    }

    private void putBody(JSONObject entry, Object[] local, Set<String> files) throws JSONException {
        String markdown = (String) local[NotesStore.CONTENT];
        entry.put("content", cloudContent(markdown, files));
        JSONObject extra = json(entry.opt("extraInfo"));
        extra.put("title", local[NotesStore.TITLE] == null ? "" : local[NotesStore.TITLE]);
        if (format.isEmpty() && !extra.has("note_content_type")) extra.put("note_content_type", "common");
        entry.put("extraInfo", extra.toString());
        if (!format.isEmpty()) {
            entry.put("snippet", CloudFormat.snippet(markdown));
            if (!entry.has("type")) entry.put("type", "mdNote");
        }
        Object updated = local[NotesStore.UPDATED];
        entry.put("modifyDate", updated instanceof Long && (Long) updated > 0 ? (Long) updated : System.currentTimeMillis());
    }

    private void updateInCloud(State.Note n, Object[] local, String hash) throws IOException {
        JSONObject entry = json(n.entry);
        JSONObject data;
        try {
            entry.put("id", n.cloudId);
            entry.put("tag", n.tag);
            putBody(entry, local, cloudFiles(entry));
            entry.put("folderId", cloudFolder((Long) local[NotesStore.FOLDER], n.cloudFolder));
            data = api.post(path("/note/" + n.cloudId), MiCloudApi.map("entry", strip(entry).toString()));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (data.optBoolean("conflict", false)) {
            // Changed in the cloud meanwhile: bring the cloud's version down as a copy; ours goes up as a new note.
            JSONObject theirs = data.optJSONObject("conflictEntry");
            String id = n.cloudId;
            state.removeNote(id);
            result.conflicts++;
            JSONObject listed = theirs != null ? theirs : new JSONObject();
            try {
                listed.put("id", id);
            } catch (JSONException ignored) {
            }
            JSONObject full = detail(id, listed);
            Content c = content(full);
            if (c != null) writeLocal(full, c, null, null, true);
            createInCloud(local);
            return;
        }
        n.tag = str(data, "tag").isEmpty() ? n.tag : str(data, "tag");
        n.modify = data.optLong("modifyDate", n.modify);
        n.hash = hash;
        n.cloudFolder = entry.optString("folderId", n.cloudFolder);
        try {
            entry.put("tag", n.tag);
            entry.put("modifyDate", n.modify);
        } catch (JSONException ignored) {
        }
        n.entry = strip(entry).toString();
        state.put(n);
        result.uploadedChanges++;
    }

    private void createInCloud(Object[] local) throws IOException {
        JSONObject entry = new JSONObject();
        JSONObject data;
        try {
            putBody(entry, local, new HashSet<>());
            Object created = local[NotesStore.CREATED];
            entry.put("createDate", created instanceof Long && (Long) created > 0 ? created : System.currentTimeMillis());
            entry.put("colorId", 0);
            entry.put("folderId", cloudFolder((Long) local[NotesStore.FOLDER], null));
            data = api.post(path("/note"), MiCloudApi.map("entry", entry.toString()));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject made = data.optJSONObject("entry");
        if (made == null || str(made, "id").isEmpty()) throw new IOException("the cloud did not create the note");
        State.Note n = new State.Note();
        n.cloudId = str(made, "id");
        n.localId = (Long) local[NotesStore.ID];
        n.tag = str(made, "tag");
        n.modify = made.optLong("modifyDate", 0L);
        n.hash = State.hash((String) local[NotesStore.TITLE], (String) local[NotesStore.CONTENT]);
        n.cloudFolder = str(made, "folderId");
        JSONObject meta = strip(made);
        try {
            if (!meta.has("extraInfo")) meta.put("extraInfo", entry.get("extraInfo"));
        } catch (JSONException ignored) {
        }
        n.entry = meta.toString();
        state.put(n);
        result.uploaded++;
    }

    /** A synced note deleted here goes to the cloud's trash (recoverable there), unless the cloud changed it since. */
    private void deleteInCloud(State.Note n) throws IOException {
        JSONObject data = api.post(path("/full/" + n.cloudId + "/delete"), MiCloudApi.map("tag", n.tag, "purge", "false"));
        state.removeNote(n.cloudId);
        if (data.optBoolean("conflict", false)) {
            // Edited in the cloud meanwhile: keep the edit and bring it back here.
            JSONObject listed = data.optJSONObject("conflictEntry");
            if (listed == null) listed = new JSONObject();
            try {
                listed.put("id", n.cloudId);
            } catch (JSONException ignored) {
            }
            JSONObject full = detail(n.cloudId, listed);
            Content c = content(full);
            if (c != null) writeLocal(full, c, null, null, false);
            result.conflicts++;
            return;
        }
        result.deletedInCloud++;
    }

    /** The cloud folder for a local folder, created in the cloud if it is new; "0" for no folder. */
    private String cloudFolder(Long localFolder, String fallback) throws IOException {
        if (localFolder == null || localFolder <= 0) return NO_FOLDER;
        String known = state.cloudFolder(localFolder);
        if (known != null) return known;
        String name = store.folderName(localFolder);
        if (name == null) return fallback != null ? fallback : NO_FOLDER;
        JSONObject entry = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            entry.put("subject", name);
            entry.put("createDate", now);
            entry.put("modifyDate", now);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject made = api.post(path("/folder"), MiCloudApi.map("entry", entry.toString())).optJSONObject("entry");
        if (made == null || str(made, "id").isEmpty()) throw new IOException("the cloud did not create folder " + name);
        state.putFolder(str(made, "id"), localFolder, str(made, "tag"), name);
        result.folders++;
        return str(made, "id");
    }

    /** The fields the website sends back for a note (it picks the same ones). */
    private JSONObject strip(JSONObject e) {
        String[] keep = format.isEmpty()
            ? new String[] {"id", "tag", "status", "createDate", "modifyDate", "colorId", "content", "setting", "folderId", "alertDate", "extraInfo"}
            : new String[] {"id", "tag", "type", "status", "createDate", "modifyDate", "colorId", "content", "folderId", "alertDate", "extraInfo", "snippet", "subNotes", "setting"};
        JSONObject out = new JSONObject();
        for (String k : keep) {
            Object v = e.opt(k);
            if (v == null) continue;
            try {
                out.put(k, v);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    String syncSummary() {
        StringBuilder sb = new StringBuilder("Synced.");
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (result.imported > 0) parts.add(result.imported + " new from the cloud");
        if (result.updated > 0) parts.add(result.updated + " updated from the cloud");
        if (result.uploaded > 0) parts.add(result.uploaded + " new sent to the cloud");
        if (result.uploadedChanges > 0) parts.add(result.uploadedChanges + (result.uploadedChanges == 1 ? " change" : " changes") + " sent to the cloud");
        if (result.deletedInCloud > 0) parts.add(result.deletedInCloud + " deleted in the cloud");
        if (result.deletedFromCloud > 0) parts.add(result.deletedFromCloud + " moved to Recently deleted");
        if (result.conflicts > 0) parts.add(result.conflicts + " changed on both sides (kept both)");
        if (result.encrypted > 0) parts.add(result.encrypted + " encrypted skipped");
        if (report.localOnlyFiles > 0) parts.add(report.localOnlyFiles + (report.localOnlyFiles == 1
            ? " picture or recording was not uploaded (it stays on this phone)" : " pictures or recordings were not uploaded (they stay on this phone)"));
        if (refused > 0) parts.add(refused + (refused == 1 ? " note was" : " notes were") + " refused by the cloud (kept here)");
        if (e2ee) parts.add("end-to-end encrypted account: nothing was uploaded");
        if (parts.isEmpty()) parts.add("Everything is up to date");
        sb.append(' ').append(join(parts)).append('.');
        return sb.toString();
    }

    // ---- when Notes opens

    /** Called by Notes' note list when it comes to the front: syncs in the background if sync is on. */
    public static void onAppOpen(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL || now - lastAppOpenRun < APP_OPEN_INTERVAL || CloudImporter.active() != null) return;
        lastCheck = now;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                State state = State.get(app);
                if (!state.flag("syncEnabled")) return;
                String cookie = state.get("session"), base = state.get("sessionBase");
                if (cookie == null || base == null) {
                    warnSignIn(app, state);
                    return;
                }
                lastAppOpenRun = System.currentTimeMillis();
                runQuietly(app, state, base, cookie, WebSettings.getDefaultUserAgent(app), CloudImportActivity.debugAllowed(base));
            } catch (Throwable t) {
                Log.w(MiCloudApi.TAG, "sync on open failed", t);
            }
        }, "micloud-sync").start();
    }

    private static void runQuietly(Context app, State state, String base, String cookie, String userAgent, boolean debug) {
        MiCloudApi api;
        try {
            api = new MiCloudApi(base, cookie, userAgent, debug, true);
        } catch (IllegalArgumentException e) {
            return;
        }
        try (NotesStore store = new NotesStore(app)) {
            CloudSync job = new CloudSync(app, api, state, store, null);
            if (!CloudImporter.claim(job)) return;
            try {
                job.sync();
                MiCloudApi.log("sync on open: " + job.syncSummary());
            } finally {
                CloudImporter.release(job);
            }
            state.set("session", api.cookie());
        } catch (MiCloudApi.SessionExpired e) {
            state.set("session", null);
            warnSignIn(app, state);
        } catch (Throwable t) {
            Log.w(MiCloudApi.TAG, "sync on open failed", t);
            state.set("lastSyncError", String.valueOf(t.getMessage()));
        }
    }

    /** Once per app run: the sync needs the user to sign in again. */
    private static void warnSignIn(Context app, State state) {
        state.set("needsSignIn", "1");
        if (warnedSignIn) return;
        warnedSignIn = true;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
            "Xiaomi Cloud sync: sign in again in Settings > Import from Xiaomi Cloud", Toast.LENGTH_LONG).show());
    }
}
