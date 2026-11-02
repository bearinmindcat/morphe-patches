package org.ungoogled.xiaominotes.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports the notes, folders and tasks of a MIUI / HyperOS local backup through Notes' own restore code: the backup's
 * record goes to NoteBackupAgent.onDataRestore and each attached file to onAttachRestore, exactly as Xiaomi's Backup app
 * hands them over on a Xiaomi phone. That code adds to what is there (folders are matched by name, notes by uuid and
 * content, old-database notes by title and text, tasks by text and creation time); it never clears the notes. Notes and
 * tasks an earlier import brought in are left out of the record first, and the settings records are not applied.
 */
public final class BackupImport {
    private static final String TAG = "UngoogledNotesImport";
    private static final String NOTES = "com.miui.notes";
    private static final String PREFS = "ungoogled_miui_backup_import";
    private static final String IMPORTED = "imported";
    private static final Object LOCK = new Object();

    /** What happened; {@link #problem} is 0 when the import ran. */
    public static final class Result {
        public int problem;
        public String otherApp;
        /** Read from the backup (after leaving out what an earlier import brought in). */
        public int backupNotes, backupFolders, backupTasks;
        /** Left out because an earlier import of this backup already brought them in. */
        public int alreadyImported;
        /** Added to this phone; -1 when the counts could not be read. */
        public int notes = -1, folders = -1, tasks = -1;
        /**
         * Of {@link #notes}, those that went to Notes' old database (backups from Notes 2.x and older): Notes offers to
         * convert them to the new format the next time its list opens.
         */
        public int toConvert;
        public int files, filesFailed;

        /** Notes in the backup that were not added: imported before or already on this phone. */
        public int skippedNotes() {
            return notes < 0 ? alreadyImported : Math.max(0, backupNotes - notes) + alreadyImported;
        }

        @Override
        public String toString() {
            return "Result{problem=" + problem + ", otherApp=" + otherApp + ", backupNotes=" + backupNotes + ", backupFolders="
                + backupFolders + ", backupTasks=" + backupTasks + ", alreadyImported=" + alreadyImported + ", notes=" + notes
                + ", folders=" + folders + ", tasks=" + tasks + ", toConvert=" + toConvert + ", skipped=" + skippedNotes() + ", files=" + files
                + ", filesFailed=" + filesFailed + "}";
        }
    }

    private BackupImport() {
    }

    /** Runs the whole import; call it off the main thread. */
    public static Result run(Context context, Uri uri) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Result result = new Result();
            File work = new File(app.getCacheDir(), "miui_backup_import");
            deleteTree(work);
            try {
                if (!work.mkdirs()) throw new IOException("cannot create " + work);
                Extracted backup = extract(app.getContentResolver(), uri, work, result);
                if (result.problem != 0) return result;
                importExtracted(app, backup, work, result);
            } catch (MiuiBackupFile.Problem p) {
                Log.w(TAG, "backup refused: " + p.getMessage());
                result.problem = p.kind;
                if (p.kind == MiuiBackupFile.OTHER_APP) result.otherApp = p.detail;
            } catch (Throwable t) {
                Log.e(TAG, "import failed", t);
                result.problem = MiuiBackupFile.BROKEN;
            } finally {
                deleteTree(work);
            }
            Log.i(TAG, "import: " + result);
            return result;
        }
    }

    // ------------------------------------------------------------------------------------------------ reading the file

    private static final class Extracted {
        File data;
        final List<String> names = new ArrayList<>();
        final List<File> files = new ArrayList<>();
    }

    private static Extracted extract(ContentResolver resolver, Uri uri, File work, Result result) throws IOException, MiuiBackupFile.Problem {
        InputStream raw = resolver.openInputStream(uri);
        if (raw == null) throw new IOException("cannot open " + uri);
        try (BufferedInputStream in = new BufferedInputStream(raw, 1 << 16)) {
            if (isZip(in)) return extractFromZip(new ZipInputStream(in), work);
            MiuiBackupFile file = new MiuiBackupFile();
            InputStream tar = file.open(in);
            if (!NOTES.equals(file.packageName) && file.packageName != null) {
                throw new MiuiBackupFile.Problem(MiuiBackupFile.OTHER_APP, file.label != null ? file.label : file.packageName);
            }
            return extractTar(tar, work);
        }
    }

    /** A zip of the backup folder (MIUI/backup/AllBackup/&lt;date&gt;/): the entry that is Notes' backup. */
    private static Extracted extractFromZip(ZipInputStream zip, File work) throws IOException, MiuiBackupFile.Problem {
        MiuiBackupFile.Problem encrypted = null;
        String otherApp = null;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            BufferedInputStream in = new BufferedInputStream(zip, 1 << 16);
            if (!MiuiBackupFile.looksLikeBackup(in)) continue;
            MiuiBackupFile file = new MiuiBackupFile();
            InputStream tar;
            try {
                tar = file.open(in);
            } catch (MiuiBackupFile.Problem p) {
                if (p.kind == MiuiBackupFile.ENCRYPTED && NOTES.equals(file.packageName)) encrypted = p;
                continue;
            }
            if (file.packageName != null && !NOTES.equals(file.packageName)) {
                otherApp = file.label != null ? file.label : file.packageName;
                continue;
            }
            return extractTar(tar, work);
        }
        if (encrypted != null) throw encrypted;
        if (otherApp != null) throw new MiuiBackupFile.Problem(MiuiBackupFile.NO_NOTES, otherApp);
        throw new MiuiBackupFile.Problem(MiuiBackupFile.NOT_A_BACKUP, "no backup in zip");
    }

    private static Extracted extractTar(InputStream tarStream, File work) throws IOException, MiuiBackupFile.Problem {
        Extracted out = new Extracted();
        File attachments = new File(work, "att");
        if (!attachments.mkdirs()) throw new IOException("cannot create " + attachments);
        String root = "apps/" + NOTES + "/";
        MiuiBackupFile.Tar tar = new MiuiBackupFile.Tar(tarStream);
        while (tar.next()) {
            if (!tar.isFile() || !tar.name.startsWith(root)) continue;
            String path = tar.name.substring(root.length());
            if (path.equals("miui_bak/_tmp_bak")) {
                out.data = new File(work, "data");
                save(tar.data(), out.data);
            } else if (path.startsWith("miui_att/")) {
                String name = path.substring("miui_att/".length());
                // The rule Xiaomi's own restore applies to names in the archive (PerformAdbRestoreTask.e).
                if (name.isEmpty() || name.contains("..") || name.contains("//") || name.indexOf('\0') >= 0) {
                    Log.w(TAG, "skipped attachment with an unsafe name: " + name);
                    continue;
                }
                File f = new File(attachments, Integer.toString(out.files.size()));
                save(tar.data(), f);
                out.names.add(name);
                out.files.add(f);
            }
        }
        if (out.data == null) throw new MiuiBackupFile.Problem(MiuiBackupFile.NO_NOTES, "no miui_bak/_tmp_bak");
        return out;
    }

    private static boolean isZip(BufferedInputStream in) throws IOException {
        in.mark(4);
        byte[] b = new byte[4];
        int n = in.read(b);
        in.reset();
        return n == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    // ------------------------------------------------------------------------------------------------ importing

    private static void importExtracted(Context app, Extracted backup, File work, Result result) throws Exception {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> imported = new HashSet<>(prefs.getStringSet(IMPORTED, new HashSet<String>()));

        BackupData data = new BackupData();
        byte[] record = data.filter(readAll(backup.data), imported, alertMode(app));
        File filtered = new File(work, "record");
        try (OutputStream o = new FileOutputStream(filtered)) {
            o.write(record);
        }
        result.backupNotes = data.notes;
        result.backupFolders = data.folders;
        result.backupTasks = data.tasks;
        result.alreadyImported = data.skippedNotes;
        if (data.notes == 0 && data.tasks == 0) {
            // Everything in this backup came in with an earlier import.
            result.notes = result.folders = result.tasks = 0;
            return;
        }

        ClassLoader loader = app.getClassLoader();
        // onDataRestore logs and swallows a record it cannot parse; check it with Notes' own parser first.
        try {
            Class<?> syncRoot = Class.forName("com.miui.notes.backup.SyncRootProtos$SyncRoot", true, loader);
            invoke(syncRoot.getMethod("parseFrom", InputStream.class), null, new java.io.ByteArrayInputStream(record));
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            Log.w(TAG, "cannot check the record", e);
        } catch (Exception e) {
            Log.w(TAG, "Notes cannot read the record", e);
            result.problem = MiuiBackupFile.BROKEN;
            return;
        }

        int[] before = counts(app);

        Class<?> agentClass = Class.forName("com.miui.notes.backup.NoteBackupAgent", true, loader);
        Class<?> metaClass = Class.forName("miui.app.backup.BackupMeta", true, loader);
        Object agent = agentClass.getDeclaredConstructor().newInstance();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        attach.invoke(agent, app);
        Object meta = metaClass.getDeclaredConstructor().newInstance();

        Method onDataRestore = agentClass.getMethod("onDataRestore", metaClass, ParcelFileDescriptor.class);
        int code;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(filtered, ParcelFileDescriptor.MODE_READ_ONLY)) {
            code = (Integer) invoke(onDataRestore, agent, meta, pfd);
        }
        if (code != 0) {
            Log.w(TAG, "onDataRestore returned " + code);
            result.problem = MiuiBackupFile.BROKEN;
            return;
        }

        // Attached files after the record: the record tells Notes where each one belongs.
        Method onAttachRestore = agentClass.getMethod("onAttachRestore", metaClass, ParcelFileDescriptor.class, String.class);
        for (int i = 0; i < backup.files.size(); i++) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(backup.files.get(i), ParcelFileDescriptor.MODE_READ_ONLY)) {
                Object r = invoke(onAttachRestore, agent, meta, pfd, backup.names.get(i));
                if (r instanceof Integer && (Integer) r != 0) result.filesFailed++;
                else result.files++;
            } catch (Exception e) {
                Log.w(TAG, "attachment " + backup.names.get(i) + " failed", e);
                result.filesFailed++;
            }
        }
        try {
            invoke(agentClass.getMethod("onRestoreEnd", metaClass), agent, meta);
        } catch (Exception e) {
            Log.w(TAG, "onRestoreEnd failed", e);
        }

        int[] after = counts(app);
        int notes = diff(before, after, 0), folders = diff(before, after, 1), oldNotes = diff(before, after, 3), oldFolders = diff(before, after, 4);
        result.toConvert = Math.max(0, oldNotes);
        result.notes = notes < 0 || oldNotes < 0 ? -1 : notes + oldNotes;
        result.folders = folders < 0 || oldFolders < 0 ? -1 : folders + oldFolders;
        result.tasks = diff(before, after, 2);
        imported.addAll(data.keys);
        prefs.edit().putStringSet(IMPORTED, imported).commit();
    }

    private static Object invoke(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new RuntimeException(c);
        }
    }

    /**
     * onDataRestore always applies the record's reminder style (AppConfig.alertMode), even when the record has no
     * settings; the setting's current value goes in so it stays as it is.
     */
    private static int alertMode(Context app) {
        boolean popup = false;
        try (Cursor c = app.getContentResolver().query(Uri.parse("content://notes/pref"), new String[]{"key", "value"},
                "key=?", new String[]{"pref_remind_type"}, null)) {
            if (c != null && c.moveToFirst()) popup = Boolean.parseBoolean(c.getString(1));
        } catch (Exception e) {
            Log.w(TAG, "reading the reminder setting failed", e);
        }
        return popup ? 1 : 2;
    }

    /**
     * {notes, folders, tasks, old-database notes, old-database folders} on this phone; -1 where a count could not be
     * read. Room's and the tasks' databases are read directly (a second connection, nothing written), the old notes
     * database through Notes' own provider, which owns it.
     */
    private static int[] counts(Context app) {
        int[] c = {-1, -1, -1, -1, -1};
        File room = app.getDatabasePath("ai_agentic_db");
        if (room.exists()) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(room.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                c[0] = count(db, "SELECT count(*) FROM documents WHERE deletedAt IS NULL AND isPrivate = 0");
                c[1] = count(db, "SELECT count(*) FROM folders WHERE deletedAt IS NULL");
            } catch (Exception e) {
                Log.w(TAG, "counting notes failed", e);
            }
        } else {
            c[0] = c[1] = 0;
        }
        File todo = app.getDatabasePath("todo.db");
        if (todo.exists()) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(todo.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                c[2] = count(db, "SELECT count(*) FROM todo WHERE local_status != 3");
            } catch (Exception e) {
                Log.w(TAG, "counting tasks failed", e);
            }
        } else {
            c[2] = 0;
        }
        c[3] = providerCount(app, "type = 0");
        c[4] = providerCount(app, "type = 1 AND _id > 0");
        return c;
    }

    private static int providerCount(Context app, String where) {
        try (Cursor cursor = app.getContentResolver().query(Uri.parse("content://notes/note"), new String[]{"_id"}, where, null, null)) {
            return cursor == null ? -1 : cursor.getCount();
        } catch (Exception e) {
            Log.w(TAG, "counting old notes failed", e);
            return -1;
        }
    }

    private static int diff(int[] before, int[] after, int i) {
        return before[i] < 0 || after[i] < 0 ? -1 : Math.max(0, after[i] - before[i]);
    }

    private static int count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (android.database.sqlite.SQLiteException e) {
            // A fresh install's database file exists before Notes has created its tables.
            if (String.valueOf(e.getMessage()).contains("no such table")) return 0;
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------------ files

    private static void save(InputStream in, File f) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE - 8));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
