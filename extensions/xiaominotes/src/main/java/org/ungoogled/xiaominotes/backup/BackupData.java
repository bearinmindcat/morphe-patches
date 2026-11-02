package org.ungoogled.xiaominotes.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Notes' backup record (miui_bak/_tmp_bak): a protobuf SyncRoot (com.miui.notes.backup.SyncRootProtos). Field numbers
 * from Notes 3.2.7.9's generated classes:
 * <pre>
 * SyncRoot   6 note (Notes, the old notes database)  7 config  8 todo (Todos)  9 todoconfig  10 modernNote (Notes, Room)
 * Notes      1 folder (NoteFolder)  2 note (NoteEntity)  3 version  4.. AI data
 * NoteEntity 1 guid  2 luid  5 createdDate  6 modifiedDate  14 title  106 uuid
 * Todos      2 todo (TodoEntity: 1 id, 8 lastModifiedTime, 9 createTime)
 * </pre>
 * The record is rewritten without the notes and tasks an earlier import already brought in (matched on their original
 * id and modification time) and with the settings records replaced by the current reminder style, so an import does not
 * change the user's settings.
 * Everything else is passed on byte for byte.
 */
final class BackupData {
    int notes;
    int folders;
    int tasks;
    /** Notes and tasks left out because an earlier import brought them in. */
    int skippedNotes;
    int skippedTasks;
    /** Keys of the notes and tasks kept, recorded after a successful import. */
    final List<String> keys = new ArrayList<>();

    /**
     * @param alertMode the reminder style to hand onDataRestore, which applies AppConfig.alertMode whether or not the
     *                  record has settings (1 pop-up, 2 notification)
     */
    byte[] filter(byte[] root, Set<String> imported, int alertMode) throws IOException {
        Writer out = new Writer();
        Reader r = new Reader(root, 0, root.length);
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            if (field == 7 || field == 9) {
                r.skip(tag);
                continue;
            }
            if ((field == 6 || field == 10 || field == 8) && (tag & 7) == 2) {
                int[] span = r.span();
                byte[] inner = field == 8 ? filterTodos(root, span[0], span[1], imported) : filterNotes(field, root, span[0], span[1], imported);
                out.bytes(field, inner);
                continue;
            }
            out.raw(r.copy(tag));
        }
        Writer config = new Writer();
        config.varint(5 << 3);
        config.varint(alertMode);
        out.bytes(7, config.done());
        return out.done();
    }

    private byte[] filterNotes(int section, byte[] b, int start, int end, Set<String> imported) throws IOException {
        Writer out = new Writer();
        Reader r = new Reader(b, start, end);
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            if (field == 2 && (tag & 7) == 2) {
                int[] span = r.span();
                String key = noteKey(section, b, span[0], span[1]);
                if (imported.contains(key)) {
                    skippedNotes++;
                } else {
                    notes++;
                    keys.add(key);
                    out.bytes(2, java.util.Arrays.copyOfRange(b, span[0], span[1]));
                }
                continue;
            }
            if (field == 1 && (tag & 7) == 2) folders++;
            out.raw(r.copy(tag));
        }
        return out.done();
    }

    private byte[] filterTodos(byte[] b, int start, int end, Set<String> imported) throws IOException {
        Writer out = new Writer();
        Reader r = new Reader(b, start, end);
        while (r.more()) {
            int tag = r.tag();
            if (tag >>> 3 == 2 && (tag & 7) == 2) {
                int[] span = r.span();
                Reader t = new Reader(b, span[0], span[1]);
                StringBuilder k = new StringBuilder("t");
                while (t.more()) {
                    int tt = t.tag();
                    int f = tt >>> 3;
                    if ((f == 1 || f == 8 || f == 9) && (tt & 7) == 0) k.append('|').append(f).append('=').append(t.varint());
                    else t.skip(tt);
                }
                String key = hash(k.toString());
                if (imported.contains(key)) {
                    skippedTasks++;
                } else {
                    tasks++;
                    keys.add(key);
                    out.bytes(2, java.util.Arrays.copyOfRange(b, span[0], span[1]));
                }
                continue;
            }
            out.raw(r.copy(tag));
        }
        return out.done();
    }

    /** The note's original identity: luid/guid/uuid plus created and modified time, hashed. */
    private static String noteKey(int section, byte[] b, int start, int end) throws IOException {
        StringBuilder k = new StringBuilder("n").append(section);
        Reader r = new Reader(b, start, end);
        while (r.more()) {
            int tag = r.tag();
            int f = tag >>> 3;
            if ((f == 1 || f == 2 || f == 106) && (tag & 7) == 2) {
                int[] span = r.span();
                k.append('|').append(f).append('=').append(new String(b, span[0], span[1] - span[0], StandardCharsets.UTF_8));
            } else if ((f == 5 || f == 6) && (tag & 7) == 0) {
                k.append('|').append(f).append('=').append(r.varint());
            } else {
                r.skip(tag);
            }
        }
        return hash(k.toString());
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 12; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static final class Reader {
        final byte[] b;
        int p;
        final int end;

        Reader(byte[] b, int start, int end) {
            this.b = b;
            this.p = start;
            this.end = end;
        }

        boolean more() {
            return p < end;
        }

        int tag() throws IOException {
            long t = varint();
            if (t <= 0 || t > Integer.MAX_VALUE) throw new IOException("bad protobuf tag");
            return (int) t;
        }

        long varint() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (p >= end) throw new IOException("truncated protobuf");
                int c = b[p++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw new IOException("bad varint");
        }

        /** Reads a length-delimited value's bounds. */
        int[] span() throws IOException {
            long len = varint();
            if (len < 0 || p + len > end) throw new IOException("truncated protobuf");
            int[] s = {p, (int) (p + len)};
            p += (int) len;
            return s;
        }

        void skip(int tag) throws IOException {
            switch (tag & 7) {
                case 0:
                    varint();
                    break;
                case 1:
                    p += 8;
                    break;
                case 2:
                    span();
                    break;
                case 5:
                    p += 4;
                    break;
                default:
                    throw new IOException("unsupported protobuf wire type " + (tag & 7));
            }
            if (p > end) throw new IOException("truncated protobuf");
        }

        /** The whole field (tag included) as bytes, for copying through unchanged. */
        byte[] copy(int tag) throws IOException {
            int valueStart = p;
            skip(tag);
            Writer w = new Writer();
            w.varint(tag);
            w.b.write(b, valueStart, p - valueStart);
            return w.done();
        }
    }

    private static final class Writer {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }

        void bytes(int field, byte[] v) {
            varint(((long) field << 3) | 2);
            varint(v.length);
            b.write(v, 0, v.length);
        }

        void raw(byte[] v) {
            b.write(v, 0, v.length);
        }

        byte[] done() {
            return b.toByteArray();
        }
    }
}
