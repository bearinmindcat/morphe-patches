package org.ungoogled.samsungnotes.transfer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Adds the notes in the picked files: .sdocx files (Samsung Notes files, as Notes' own Share > Samsung Notes file makes
 * them) and zips of them, our export included. Every note goes through Notes' own import (ImportSdocXTask), which never
 * touches the notes already here. Folders come from the export's index or the zip's folders. Notes that are already
 * here (same creation and change time and the same text) and locked notes are skipped.
 */
final class NotesImport {
    static final String INDEX = "notes.json";
    static final String FORMAT = "org.ungoogled.samsungnotes.export";
    private static final String TAG = "UA";

    static final class Result {
        int imported;
        int skipped;
        int locked;
        int failed;
        int notNotes;
        int folders;
    }

    interface Progress {
        void update(int done, int total);
    }

    /** One note to import: a file of its own, or an entry of a zip. */
    private static final class Item {
        File file;
        ZipFile zip;
        String entry;
        List<String> folder = Collections.emptyList();
    }

    private NotesImport() {
    }

    static Result run(Context context, List<Uri> uris, Progress progress) {
        Result r = new Result();
        File dir = new File(context.getCacheDir(), "ungoogled_notes_import");
        delete(dir);
        dir.mkdirs();
        List<ZipFile> zips = new ArrayList<>();
        try {
            NotesBridge.initSdk(context);
            int[] before = NotesData.counts(context);
            List<Item> items = new ArrayList<>();
            int n = 0;
            for (Uri uri : uris) {
                File source = new File(dir, "source" + (n++));
                String name = displayName(context, uri);
                try {
                    copy(context, uri, source);
                } catch (IOException | SecurityException e) {
                    Log.w(TAG, "notes import: can't read " + name + ": " + e);
                    r.failed++;
                    continue;
                }
                collect(source, name, items, zips, r);
            }
            Map<String, String> folderUuids = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                if (progress != null) progress.update(i, items.size());
                importItem(context, items.get(i), dir, folderUuids, r);
            }
            int[] after = NotesData.counts(context);
            r.folders = Math.max(0, after[1] - before[1]);
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "notes import failed", e);
            r.failed++;
        } finally {
            for (ZipFile zip : zips) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Deleted with the directory.
                }
            }
            delete(dir);
        }
        return r;
    }

    /** Sorts a picked file into notes: a note itself, or a zip of notes. */
    private static void collect(File source, String name, List<Item> items, List<ZipFile> zips, Result r) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip")) {
            File note = new File(source.getPath() + ".sdocx");
            if (source.renameTo(note) && (lower.endsWith(".sdocx") || NotesBridge.valid(note.getPath()))) {
                Item item = new Item();
                item.file = note;
                items.add(item);
                return;
            }
            source = note.exists() ? note : source;
        }
        // A zip of notes (an .sdocx is a zip too, but one without .sdocx files in it).
        ZipFile zip;
        try {
            zip = new ZipFile(source);
        } catch (IOException e) {
            r.notNotes++;
            return;
        }
        Map<String, List<String>> index = index(zip);
        int found = 0;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            String path = entry.getName().replace('\\', '/');
            String file = path.substring(path.lastIndexOf('/') + 1);
            if (entry.isDirectory() || !file.toLowerCase(Locale.ROOT).endsWith(".sdocx")) continue;
            // macOS keeps resource forks of zipped files as __MACOSX/._name.
            if (file.startsWith("._") || path.startsWith("__MACOSX/")) continue;
            Item item = new Item();
            item.zip = zip;
            item.entry = entry.getName();
            List<String> folder = index.get(path);
            item.folder = folder != null ? folder : folderOf(path);
            items.add(item);
            found++;
        }
        if (found == 0) {
            r.notNotes++;
            try {
                zip.close();
            } catch (IOException ignored) {
                // Deleted with the directory.
            }
        } else {
            zips.add(zip);
        }
    }

    /** Our export's index: zip path -> exact folder names. */
    private static Map<String, List<String>> index(ZipFile zip) {
        Map<String, List<String>> index = new HashMap<>();
        ZipEntry entry = zip.getEntry(INDEX);
        if (entry == null) return index;
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > (8 << 20)) return index;
            }
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!FORMAT.equals(json.optString("format"))) return index;
            JSONArray notes = json.optJSONArray("notes");
            for (int i = 0; notes != null && i < notes.length(); i++) {
                JSONObject note = notes.optJSONObject(i);
                if (note == null) continue;
                JSONArray folder = note.optJSONArray("folder");
                List<String> names = new ArrayList<>();
                for (int j = 0; folder != null && j < folder.length(); j++) {
                    String name = clean(folder.optString(j));
                    if (name != null) names.add(name);
                }
                index.put(note.optString("file"), names);
            }
        } catch (Exception e) {
            Log.w(TAG, "notes import: unreadable index: " + e);
        }
        return index;
    }

    /** The folders of a path inside a zip ("Work/Projects/a.sdocx" -> Work, Projects). */
    private static List<String> folderOf(String path) {
        List<String> names = new ArrayList<>();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            String name = clean(parts[i]);
            if (name != null) names.add(name);
        }
        return names;
    }

    private static String clean(String name) {
        if (name == null) return null;
        name = name.replace('/', '_').trim();
        return name.isEmpty() || name.equals(".") || name.equals("..") ? null : name;
    }

    private static void importItem(Context context, Item item, File dir, Map<String, String> folderUuids, Result r) {
        File file = item.file;
        try {
            if (file == null) {
                file = new File(dir, "entry.sdocx");
                try (InputStream in = item.zip.getInputStream(item.zip.getEntry(item.entry))) {
                    write(in, file);
                }
            }
            String path = file.getPath();
            if (!NotesBridge.valid(path)) {
                Log.w(TAG, "notes import: not a Samsung Notes file: " + item.entry);
                r.notNotes++;
                return;
            }
            // A locked note would need its password and Samsung's lock to open; it is left out.
            if (NotesBridge.locked(path)) {
                r.locked++;
                return;
            }
            if (alreadyHere(context, path)) {
                r.skipped++;
                return;
            }
            String key = String.join("/", item.folder);
            String folder = folderUuids.get(key);
            if (folder == null) {
                folder = NotesBridge.folder(item.folder);
                folderUuids.put(key, folder);
            }
            if (NotesBridge.importSdocx(context, path, folder)) {
                r.imported++;
            } else {
                r.failed++;
            }
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "notes import: " + (item.entry != null ? item.entry : "file") + " failed", e);
            r.failed++;
        } finally {
            if (file != null) file.delete();
        }
    }

    /** A note with the same creation and change time and the same text is already here. */
    private static boolean alreadyHere(Context context, String path) {
        long[] times;
        try {
            times = NotesBridge.times(path);
        } catch (Exception | LinkageError e) {
            return false;
        }
        List<String> same = NotesData.withTimes(context, times[0], times[1]);
        if (same.isEmpty()) return false;
        String text = NotesBridge.bodyText(path);
        for (String other : same) {
            String otherText = NotesBridge.bodyText(other);
            // Times to the millisecond already match; the text only tells apart notes it can read.
            if (text == null || otherText == null || text.equals(otherText)) return true;
        }
        return false;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
            // Falls back to the address.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    private static void copy(Context context, Uri uri, File to) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            write(in, to);
        }
    }

    private static void write(InputStream in, File to) throws IOException {
        try (OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
        }
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
