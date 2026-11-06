package miui.util;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures HyperOS launcher uses, answering "absent" off HyperOS (no Xiaomi code). */
public class HashUtils {
    public static java.lang.String getHash(java.lang.String p0, java.lang.String p1) {
        if (p0 == null || p1 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance(p1).digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder h = new StringBuilder(); for (byte x : d) h.append(String.format("%02x", x & 0xff)); return h.toString(); } catch (java.security.NoSuchAlgorithmException e) { return null; }
    }
    public static java.lang.String getSHA1(java.io.File p0) {
        return "";
    }
}
