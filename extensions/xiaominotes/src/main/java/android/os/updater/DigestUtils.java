package android.os.updater;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Notes uses, answering "absent" off HyperOS (no Xiaomi code). */
public class DigestUtils {
    public static byte[] get(java.io.InputStream p0, java.lang.String p1) {
        try { java.security.MessageDigest m = java.security.MessageDigest.getInstance(p1); byte[] b = new byte[8192]; for (int n; (n = p0.read(b)) > 0; ) m.update(b, 0, n); return m.digest(); } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { return null; }
    }
    public static java.lang.String toHexReadable(byte[] p0) {
        if (p0 == null) return null; byte[] d = p0; StringBuilder h = new StringBuilder(); for (byte x : d) h.append(String.format("%02x", x & 0xff)); return h.toString();
    }
}
