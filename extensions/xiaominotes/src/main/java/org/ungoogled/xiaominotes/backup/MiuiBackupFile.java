package org.ungoogled.xiaominotes.backup;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/**
 * Reads a MIUI / HyperOS local backup file ("Notes(com.miui.notes).bak" in MIUI/backup/AllBackup/&lt;date&gt;/).
 *
 * Layout, as Xiaomi's Backup app writes it (com.miui.backup 7.2.1.1: FullBackupUtils.f/.d/.e, BRManager.d) and reads
 * it back (BackupFileResolver.c, PerformAdbRestoreTask.h):
 * <pre>
 * MIUI BACKUP\n
 * 2\n                          container version (1 = the pre-2015 folder format, not a single file)
 * &lt;package&gt;[ &lt;app label&gt;]\n
 * &lt;feature&gt;\n                  -1 = full backup
 * &lt;flag&gt;\n                     0 plain, 1 encrypted (+1 line), 20 plain + user id line, 21 encrypted + 1 line + user id line
 * ANDROID BACKUP\n             then Android's own backup header:
 * &lt;version&gt;\n                  1..5
 * &lt;compressed&gt;\n               1 = zlib stream follows
 * none|AES-256\n
 * tar                          apps/&lt;package&gt;/miui_bak/_tmp_bak, apps/&lt;package&gt;/miui_att/..., ...
 * </pre>
 * Encrypted backups use a random password that the Backup app seals with the Xiaomi account (EncryptPassword.e/.c),
 * so they cannot be opened without Xiaomi's servers; they are reported, not read.
 */
final class MiuiBackupFile {
    static final String MAGIC = "MIUI BACKUP\n";
    private static final String ANDROID_MAGIC = "ANDROID BACKUP";

    /** A reason the file cannot be imported, shown to the user. */
    static final class Problem extends Exception {
        final int kind;
        final String detail;

        Problem(int kind, String detail) {
            super(kind + ": " + detail);
            this.kind = kind;
            this.detail = detail;
        }
    }

    static final int NOT_A_BACKUP = 1;
    static final int OTHER_APP = 2;
    static final int ENCRYPTED = 3;
    static final int UNSUPPORTED = 4;
    static final int BROKEN = 5;
    static final int NO_NOTES = 6;

    String packageName;
    String label;

    /** Reads both headers from {@code in} and returns the tar stream that follows. */
    InputStream open(InputStream in) throws IOException, Problem {
        byte[] magic = new byte[MAGIC.length()];
        readFully(in, magic);
        String first = new String(magic, StandardCharsets.ISO_8859_1);
        if (first.equals(MAGIC)) {
            String version = line(in);
            if (!"2".equals(version)) throw new Problem(UNSUPPORTED, "MIUI backup version " + version);
            String app = line(in);
            int space = app.indexOf(' ');
            packageName = space < 0 ? app : app.substring(0, space);
            label = space < 0 ? null : app.substring(space + 1);
            line(in); // feature
            String flag = line(in);
            switch (flag) {
                case "0":
                    break;
                case "20":
                    line(in); // user id
                    break;
                case "1":
                case "21":
                    throw new Problem(ENCRYPTED, flag);
                default:
                    throw new Problem(UNSUPPORTED, "MIUI backup flag " + flag);
            }
            if (!ANDROID_MAGIC.equals(line(in))) throw new Problem(BROKEN, "no Android backup header");
        } else if (first.equals(ANDROID_MAGIC.substring(0, MAGIC.length()))) {
            // A plain Android backup (adb backup); the rest of the magic line follows.
            if (!ANDROID_MAGIC.substring(MAGIC.length()).equals(line(in))) throw new Problem(NOT_A_BACKUP, "magic");
        } else {
            throw new Problem(NOT_A_BACKUP, "magic");
        }
        int version;
        try {
            version = Integer.parseInt(line(in));
        } catch (NumberFormatException e) {
            throw new Problem(BROKEN, "Android backup version");
        }
        if (version < 1 || version > 5) throw new Problem(UNSUPPORTED, "Android backup version " + version);
        boolean compressed = !"0".equals(line(in));
        String encryption = line(in);
        if (!"none".equals(encryption)) throw new Problem(ENCRYPTED, encryption);
        return compressed ? new InflaterInputStream(in, new java.util.zip.Inflater(), 65536) : in;
    }

    /** True when the stream starts with a MIUI or Android backup header (the stream must support mark). */
    static boolean looksLikeBackup(BufferedInputStream in) throws IOException {
        in.mark(32);
        byte[] b = new byte[MAGIC.length()];
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
        }
        in.reset();
        String s = new String(b, 0, n, StandardCharsets.ISO_8859_1);
        return s.equals(MAGIC) || s.equals(ANDROID_MAGIC.substring(0, MAGIC.length()));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(32);
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("header");
            if (c == '\n') break;
            if (b.size() > 4096) throw new IOException("header line too long");
            b.write(c);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) throw new EOFException();
            n += r;
        }
    }

    /**
     * POSIX tar as Android's FullBackup.backupToTar writes it (ustar; pax 'x' records for long names and big files),
     * plus GNU long names for good measure.
     */
    static final class Tar {
        private final InputStream in;
        private long remaining;
        private long padding;
        String name;
        long size;
        char type;

        Tar(InputStream in) {
            this.in = in;
        }

        /** Moves to the next entry; false at the end of the archive. */
        boolean next() throws IOException {
            skip(remaining + padding);
            remaining = padding = 0;
            String longName = null;
            Long paxSize = null;
            byte[] h = new byte[512];
            while (true) {
                if (!fill(h)) return false;
                if (isZero(h)) return false;
                if (!checksumOk(h)) throw new IOException("bad tar header");
                char t = (char) (h[156] == 0 ? '0' : h[156]);
                long sz = octal(h, 124, 12);
                if (t == 'x' || t == 'L') {
                    if (sz > 1 << 20) throw new IOException("tar extended header too big");
                    byte[] ext = new byte[(int) sz];
                    readFully(in, ext);
                    skip(pad(sz));
                    if (t == 'L') {
                        longName = cString(ext, 0, ext.length);
                    } else {
                        String[] pax = parsePax(ext);
                        if (pax[0] != null) longName = pax[0];
                        if (pax[1] != null) paxSize = Long.parseLong(pax[1]);
                    }
                    continue;
                }
                if (t == 'g') {
                    skip(sz + pad(sz));
                    continue;
                }
                String n = cString(h, 0, 100);
                // POSIX ustar ("ustar\0"): names longer than 100 bytes continue in the prefix field. GNU's "ustar " uses
                // those bytes for other things.
                if (h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r' && h[262] == 0) {
                    String prefix = cString(h, 345, 155);
                    if (!prefix.isEmpty()) n = prefix + "/" + n;
                }
                name = longName != null ? longName : n;
                size = paxSize != null ? paxSize : sz;
                type = t;
                remaining = (t == '0' || t == '7') ? size : 0;
                padding = (t == '0' || t == '7') ? pad(size) : 0;
                if (t != '0' && t != '7' && size > 0 && t != '5') {
                    // Links and the like carry no data we use, but a size means data blocks follow.
                    remaining = size;
                    padding = pad(size);
                }
                return true;
            }
        }

        /** The current entry's data. */
        InputStream data() {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int c = in.read();
                    if (c >= 0) remaining--;
                    return c;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int r = in.read(b, off, (int) Math.min(len, remaining));
                    if (r > 0) remaining -= r;
                    return r;
                }

                @Override
                public void close() {
                }
            };
        }

        boolean isFile() {
            return type == '0' || type == '7';
        }

        private static long pad(long size) {
            return (512 - (size % 512)) % 512;
        }

        private boolean fill(byte[] h) throws IOException {
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) {
                    if (n == 0) return false;
                    throw new EOFException("truncated tar");
                }
                n += r;
            }
            return true;
        }

        private void skip(long n) throws IOException {
            byte[] b = new byte[8192];
            while (n > 0) {
                int r = in.read(b, 0, (int) Math.min(b.length, n));
                if (r < 0) throw new EOFException("truncated tar");
                n -= r;
            }
        }

        private static boolean isZero(byte[] h) {
            for (byte b : h) if (b != 0) return false;
            return true;
        }

        private static boolean checksumOk(byte[] h) {
            long stored;
            try {
                stored = octal(h, 148, 8);
            } catch (IOException e) {
                return false;
            }
            long unsigned = 0, signed = 0;
            for (int i = 0; i < 512; i++) {
                int v = (i >= 148 && i < 156) ? ' ' : h[i];
                unsigned += v & 0xff;
                signed += v;
            }
            return stored == unsigned || stored == signed;
        }

        private static long octal(byte[] h, int off, int len) throws IOException {
            if ((h[off] & 0x80) != 0) {
                // base-256 (GNU) for very large values
                long v = h[off] & 0x7f;
                for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xff);
                return v;
            }
            long v = 0;
            int i = off, end = off + len;
            while (i < end && (h[i] == ' ' || h[i] == 0)) i++;
            for (; i < end; i++) {
                byte c = h[i];
                if (c == 0 || c == ' ') break;
                if (c < '0' || c > '7') throw new IOException("bad octal");
                v = (v << 3) + (c - '0');
            }
            return v;
        }

        private static String cString(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        /** Returns {path, size} from a pax extended header ("len key=value\n" records). */
        private static String[] parsePax(byte[] ext) {
            String[] out = new String[2];
            int p = 0;
            while (p < ext.length) {
                int sp = p;
                while (sp < ext.length && ext[sp] != ' ') sp++;
                if (sp >= ext.length) break;
                int len;
                try {
                    len = Integer.parseInt(new String(ext, p, sp - p, StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    break;
                }
                if (len <= 0 || p + len > ext.length) break;
                String record = new String(ext, sp + 1, p + len - sp - 2, StandardCharsets.UTF_8);
                int eq = record.indexOf('=');
                if (eq > 0) {
                    String key = record.substring(0, eq);
                    if (key.equals("path")) out[0] = record.substring(eq + 1);
                    else if (key.equals("size")) out[1] = record.substring(eq + 1);
                }
                p += len;
            }
            return out;
        }
    }
}
