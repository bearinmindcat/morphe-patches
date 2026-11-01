package android.os.updater;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Notes uses, answering "absent" off HyperOS (no Xiaomi code). */
public class MD5 {
    public static java.lang.String getMd5Digest(java.lang.String p0) {
        if (p0 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance("MD5").digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder h = new StringBuilder(); for (byte x : d) h.append(String.format("%02x", x & 0xff)); return h.toString(); } catch (java.security.NoSuchAlgorithmException e) { return null; }
    }
}
