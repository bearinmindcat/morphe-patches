package org.ungoogled.samsungnotes.transfer;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Notes' own import and export code, called by its kept names (the patch checks that they exist in this build).
 * Import is what Notes runs for a shared .sdocx (ImportSdocXTask); export is its own share code (MakeUriHelper.makeSDoc,
 * makeText, ShareHandler.makePDFFiles). Everything here runs on a worker thread.
 */
final class NotesBridge {
    static final String NT = "com.samsung.android.support.senl.nt.";
    static final String UNCATEGORIZED = "uncategorized:///";
    private static final String FILE = "com.samsung.android.sdk.pen.worddoc.SpenWNoteFile";
    private static final String WNOTE = "com.samsung.android.sdk.pen.worddoc.SpenWNote";
    private static final String SHARE_DATA = NT + "composer.main.base.model.share.ShareData";

    private NotesBridge() {
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, NotesBridge.class.getClassLoader());
    }

    private static Object call(String cls, String name, Class<?>[] types, Object target, Object... args) throws Exception {
        Method m = type(cls).getMethod(name, types);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    /** The S Pen document SDK, which reading and writing notes needs. */
    static void initSdk(Context context) throws Exception {
        call(NT + "base.common.sdk.util.SpenSdkInitializer", "initialize", new Class<?>[]{Context.class}, null, context);
    }

    /** Whether the file is a Samsung Notes document the SDK can open. */
    static boolean valid(String path) {
        try {
            return Boolean.TRUE.equals(call(FILE, "isValid", new Class<?>[]{String.class}, null, path));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** Whether the note is locked (password or biometrics). */
    static boolean locked(String path) {
        try {
            return Boolean.TRUE.equals(call(FILE, "isLocked", new Class<?>[]{String.class}, null, path));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** {created, modified} in Notes' database time, as an import of this file stores them. */
    static long[] times(String path) throws Exception {
        String time = NT + "base.common.TimeManager";
        long created = (Long) call(FILE, "getDisplayCreatedTime", new Class<?>[]{String.class}, null, path);
        long modified = (Long) call(FILE, "getDisplayModifiedTime", new Class<?>[]{String.class}, null, path);
        return new long[]{
            (Long) call(time, "convertSdkTimeToAppTime", new Class<?>[]{long.class}, null, created),
            (Long) call(time, "convertSdkTimeToAppTime", new Class<?>[]{long.class}, null, modified),
        };
    }

    /** The note's body text, or null when it can't be read. */
    static String bodyText(String path) {
        try {
            Object text = call(FILE, "getBodyTextString", new Class<?>[]{String.class}, null, path);
            return text instanceof String ? (String) text : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * The folder with these names ("Work", "Projects"), made where missing (FolderManager.findAndMakeCategory, Notes'
     * own lookup for restores); Uncategorized for none.
     */
    static String folder(List<String> names) throws Exception {
        if (names == null || names.isEmpty()) return UNCATEGORIZED;
        StringBuilder path = new StringBuilder(UNCATEGORIZED);
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) path.append('/');
            path.append(names.get(i));
        }
        String manager = NT + "model.folder.FolderManager";
        Object instance = call(manager, "getInstance", new Class<?>[0], null);
        Object uuid = call(manager, "findAndMakeCategory", new Class<?>[]{String.class}, instance, path.toString());
        return uuid instanceof String && !((String) uuid).isEmpty() ? (String) uuid : UNCATEGORIZED;
    }

    /** Adds the .sdocx at [path] as a new note in [folderUuid] (Notes' ImportSdocXTask, as for a shared file). */
    static boolean importSdocx(Context context, String path, String folderUuid) throws Exception {
        String uuid = (String) call(NT + "base.common.util.UUIDUtils", "newUUID", new Class<?>[]{Context.class}, null, context);
        Class<?> paramType = type(NT + "model.importer.task.param.ImportSdocXParam");
        Object param = paramType.getConstructor().newInstance();
        String base = NT + "model.importer.task.param.ImportBaseParam";
        call(base, "setContext", new Class<?>[]{Context.class}, param, context);
        call(base, "setUuid", new Class<?>[]{String.class}, param, uuid);
        call(base, "setExternalPath", new Class<?>[]{String.class}, param, path);
        call(base, "setFolderUuid", new Class<?>[]{String.class}, param, folderUuid);
        call(base, "setCaller", new Class<?>[]{String.class}, param, "TriggerImportTask");
        Object task;
        try {
            task = type(NT + "model.importer.task.ImportSdocXTask").getConstructor(paramType).newInstance(param);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
        String importTask = NT + "model.importer.task.ImportTask";
        call(importTask, "setImported", new Class<?>[]{boolean.class}, task, true);
        return Boolean.TRUE.equals(call(importTask, "call", new Class<?>[0], task));
    }

    // ---- export

    private static Object shareData(NotesData.Note n) throws Exception {
        return type(SHARE_DATA)
            .getConstructor(String.class, String.class, String.class, long.class, long.class, String.class)
            .newInstance(n.path, n.uuid, n.title, n.created, n.modified, "");
    }

    private static Object makeUriHelper() throws Exception {
        Class<?> cache = type(NT + "composer.main.base.presenter.share.ShareCacheHelper");
        return type(NT + "composer.main.base.presenter.share.MakeUriHelper").getConstructor(cache).newInstance(cache.getConstructor().newInstance());
    }

    /** The note as a Samsung Notes file at [out] (MakeUriHelper.makeSDoc, Notes' Share > Samsung Notes file). */
    static boolean sdocx(Context context, NotesData.Note n, String out) throws Exception {
        Object helper = makeUriHelper();
        Object result = call(helper.getClass().getName(), "makeSDoc", new Class<?>[]{Context.class, type(SHARE_DATA), String.class},
            helper, context, shareData(n), out);
        return result != null;
    }

    /** The note's text at [out] (MakeUriHelper.makeText); false for a note without text. */
    static boolean text(Context context, NotesData.Note n, String out) throws Exception {
        Object note = call(NT + "app.common.util.NotesUtils", "getSpenWNote", new Class<?>[]{Context.class, String.class, boolean.class},
            null, context, n.path, false);
        if (note == null) return false;
        try {
            Object helper = makeUriHelper();
            Object result = call(helper.getClass().getName(), "makeText", new Class<?>[]{Context.class, type(WNOTE), String.class},
                helper, context, note, out);
            return result instanceof String && !((String) result).isEmpty();
        } finally {
            close(n.path, note);
        }
    }

    private static void close(String path, Object note) {
        try {
            String manager = NT + "model.documents.file.DocumentFileManager";
            Object instance = call(manager, "getInstance", new Class<?>[0], null);
            call(manager, "close", new Class<?>[]{String.class, type(WNOTE), boolean.class}, instance, path, note, true);
        } catch (Exception | LinkageError ignored) {
            // Closed with the process at worst.
        }
    }

    private static final String PDF_EXPORT = "com.samsung.android.sdk.composer.pdf.SpenNotePdfExport";

    /** A PDF exporter as Notes' share code makes one; close it after the last note. */
    static Object pdfExporter(Context context) throws Exception {
        try {
            call(NT + "base.common.sdk.util.SpenComposerSdkInitializer", "initialize", new Class<?>[]{Context.class}, null, context);
        } catch (Exception ignored) {
            // makePDFFiles initialises it again itself.
        }
        return type(PDF_EXPORT).getConstructor(Context.class, int.class).newInstance(context, 1);
    }

    static void closePdfExporter(Object exporter) {
        if (exporter == null) return;
        try {
            exporter.getClass().getMethod("close").invoke(exporter);
        } catch (Exception | LinkageError ignored) {
            // Already closed.
        }
    }

    /** The note as a PDF in [dir] (ShareHandler.makePDFFiles); the file it wrote, or null. */
    @SuppressWarnings("unchecked")
    static String pdf(Context context, Object exporter, NotesData.Note n, String dir) throws Exception {
        String handler = NT + "app.main.noteslist.presenter.task.ShareHandler";
        Object instance = type(handler).getConstructor().newInstance();
        List<String> files = (List<String>) call(handler, "makePDFFiles",
            new Class<?>[]{Context.class, type(PDF_EXPORT), List.class, String.class, type(handler + "$Callback")},
            instance, context, exporter, Collections.singletonList(shareData(n)), dir, null);
        return files != null && !files.isEmpty() ? files.get(0) : null;
    }
}
