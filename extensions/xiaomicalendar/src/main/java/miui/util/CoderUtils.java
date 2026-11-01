package miui.util;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Calendar uses, answering "absent" off HyperOS (no Xiaomi code). */
public class CoderUtils {
    public static java.lang.String base64AesEncode(java.lang.String p0, java.lang.String p1) {
        if (p0 == null || p1 == null) return null; try { javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(p1.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec("0102030405060708".getBytes(java.nio.charset.StandardCharsets.UTF_8))); return android.util.Base64.encodeToString(c.doFinal(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP); } catch (java.security.GeneralSecurityException | IllegalArgumentException e) { return null; }
    }
    public static java.lang.String base6AesDecode(java.lang.String p0, java.lang.String p1) {
        if (p0 == null || p1 == null) return null; try { javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(p1.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec("0102030405060708".getBytes(java.nio.charset.StandardCharsets.UTF_8))); return new String(c.doFinal(android.util.Base64.decode(p0, android.util.Base64.DEFAULT)), java.nio.charset.StandardCharsets.UTF_8); } catch (java.security.GeneralSecurityException | IllegalArgumentException e) { return null; }
    }
    public static java.lang.String encodeSHA(java.lang.String p0) {
        if (p0 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder h = new StringBuilder(); for (byte x : d) h.append(String.format("%02x", x & 0xff)); return h.toString(); } catch (java.security.NoSuchAlgorithmException e) { return null; }
    }
}
