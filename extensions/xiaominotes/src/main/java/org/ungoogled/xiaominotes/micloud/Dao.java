package org.ungoogled.xiaominotes.micloud;

/**
 * Notes' own database calls, which R8 renamed. The "Import from Xiaomi Cloud" patch replaces these bodies with calls to
 * the methods it finds in this Notes build: the note insert that Notes' external-note provider uses (INSERT OR REPLACE
 * through Room, so the note list refreshes), the folder insert Notes' backup restore uses, and Notes' own converter
 * from Xiaomi Cloud's note format to its Markdown. Unpatched they throw, so nothing is written by mistake.
 */
final class Dao {
    private Dao() {
    }

    /** Inserts [note] (a NoteEntity) into Notes' AppDatabase [db]; with a non-zero id it replaces that note. */
    static long putNote(Object db, Object note) {
        throw new IllegalStateException("Xiaomi Cloud import: database bridge not patched");
    }

    /** Inserts [folder] (a FolderEntity) into Notes' AppDatabase [db] and returns its id. */
    static long addFolder(Object db, Object folder) {
        throw new IllegalStateException("Xiaomi Cloud import: database bridge not patched");
    }

    /** Converts a Xiaomi Cloud note body (MIUI rich text or plain text) to Notes' Markdown. */
    static String toMarkdown(String content) {
        throw new IllegalStateException("Xiaomi Cloud import: converter bridge not patched");
    }
}
