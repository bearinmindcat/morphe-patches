package org.ungoogled.samsungnotes.transfer;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes every unlocked note (not in Trash) as a Samsung Notes file (.sdocx, Notes' own Share > Samsung Notes file) into
 * one zip, in folders named after the note's folders, optionally with a PDF and a text copy of each. The zip's index
 * (notes.json) keeps the exact folder names for Import notes. Locked notes are left out: Notes would write them out
 * unlocked.
 */
final class NotesExport {
    private static final String TAG = "UA";

    static final class Result {
        int exported;
        int locked;
        int failed;
        boolean writeFailed;
    }

    private NotesExport() {
    }

    /** {notes to export, locked notes}. */
    static int[] preview(Context context) {
        int notes = 0;
        int locked = 0;
        for (NotesData.Note n : NotesData.notes(context)) {
            if (n.locked) locked++;
            else notes++;
        }
        return new int[]{notes, locked};
    }

    static Result run(Context context, Uri target, boolean pdf, boolean text, NotesImport.Progress progress) {
        Result r = new Result();
        File dir = new File(context.getCacheDir(), "ungoogled_notes_export");
        NotesImport.delete(dir);
        dir.mkdirs();
        Object exporter = null;
        try (OutputStream raw = context.getContentResolver().openOutputStream(target, "w")) {
            if (raw == null) throw new IOException("no output stream");
            NotesBridge.initSdk(context);
            List<NotesData.Note> notes = NotesData.notes(context);
            JSONArray index = new JSONArray();
            Set<String> used = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                for (int i = 0; i < notes.size(); i++) {
                    if (progress != null) progress.update(i, notes.size());
                    NotesData.Note n = notes.get(i);
                    if (n.locked || NotesBridge.locked(n.path)) {
                        r.locked++;
                        continue;
                    }
                    if (n.path == null || !new File(n.path).exists()) {
                        r.failed++;
                        continue;
                    }
                    String base = unique(folderPath(n) + fileName(n), used);
                    File note = new File(dir, "note.sdocx");
                    boolean made;
                    try {
                        made = NotesBridge.sdocx(context, n, note.getPath()) && note.exists();
                    } catch (Exception | LinkageError e) {
                        Log.w(TAG, "notes export: a note failed", e);
                        made = false;
                    }
                    if (!made) {
                        note.delete();
                        r.failed++;
                        continue;
                    }
                    put(zip, base + ".sdocx", note, n.modified);
                    note.delete();
                    JSONObject entry = new JSONObject();
                    entry.put("file", base + ".sdocx");
                    entry.put("folder", new JSONArray(n.folder));
                    index.put(entry);
                    r.exported++;
                    if (text) {
                        File txt = new File(dir, "note.txt");
                        try {
                            made = NotesBridge.text(context, n, txt.getPath()) && txt.exists();
                        } catch (Exception | LinkageError e) {
                            Log.w(TAG, "notes export: text copy failed", e);
                            made = false;
                        }
                        if (made) put(zip, base + ".txt", txt, n.modified);
                        txt.delete();
                    }
                    if (pdf) {
                        File pdfDir = new File(dir, "pdf");
                        pdfDir.mkdirs();
                        String file = null;
                        try {
                            if (exporter == null) exporter = NotesBridge.pdfExporter(context);
                            file = NotesBridge.pdf(context, exporter, n, pdfDir.getPath());
                        } catch (Exception | LinkageError e) {
                            Log.w(TAG, "notes export: PDF copy failed", e);
                        }
                        if (file != null && new File(file).exists()) put(zip, base + ".pdf", new File(file), n.modified);
                        NotesImport.delete(pdfDir);
                    }
                }
                JSONObject json = new JSONObject();
                json.put("format", NotesImport.FORMAT);
                json.put("version", 1);
                json.put("notes", index);
                ZipEntry entry = new ZipEntry(NotesImport.INDEX);
                zip.putNextEntry(entry);
                zip.write(json.toString(1).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "notes export failed", e);
            r.writeFailed = true;
        } finally {
            NotesBridge.closePdfExporter(exporter);
            NotesImport.delete(dir);
        }
        return r;
    }

    private static void put(ZipOutputStream zip, String name, File file, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (time > 0) entry.setTime(time);
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private static String folderPath(NotesData.Note n) {
        StringBuilder path = new StringBuilder();
        for (String name : n.folder) {
            String safe = safe(name, 60);
            path.append(safe.isEmpty() ? "_" : safe).append('/');
        }
        return path.toString();
    }

    /** The note's title as a file name, else its first line of text, else "Note". */
    private static String fileName(NotesData.Note n) {
        String name = safe(n.title, 80);
        if (name.isEmpty()) {
            String body = NotesBridge.bodyText(n.path);
            if (body != null) {
                for (String line : body.split("\n")) {
                    name = safe(line, 40);
                    if (!name.isEmpty()) break;
                }
            }
        }
        return name.isEmpty() ? "Note" : name;
    }

    /** Characters other systems' file names can't hold become "_". */
    private static String safe(String s, int max) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|\u200b\ufffc".indexOf(c) >= 0) {
                if (c == '\u200b' || c == '\ufffc') continue;
                c = '_';
            }
            out.append(c);
        }
        String result = out.toString().replaceAll("\\s+", " ").trim();
        while (result.startsWith(".")) result = result.substring(1).trim();
        if (result.length() > max) result = result.substring(0, max).trim();
        while (result.endsWith(".")) result = result.substring(0, result.length() - 1).trim();
        return result;
    }

    private static String unique(String base, Set<String> used) {
        String name = base;
        for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) name = base + " (" + i + ")";
        return name;
    }
}
