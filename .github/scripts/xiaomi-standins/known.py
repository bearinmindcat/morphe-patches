# Shared answers for Xiaomi framework members, used by gen.py instead of the plain "absent" default. A stand-in only
# ever gets the members its app references; these are what those members answer off HyperOS. Keys are dex-style:
# "Lowner;->name:type" (field initializer, a Java expression) or "Lowner;->name(params)return" (method body, Java
# statements; parameters are p0, p1, ...). Off HyperOS the apps should behave like Xiaomi's Global build on a phone
# that is not Xiaomi's: international, region = the phone's locale, no MIUI-only hardware.

REGION = 'java.util.Locale.getDefault().getCountry()'
HEX_LOOP = 'StringBuilder h = new StringBuilder(); for (byte x : d) h.append(String.format("%02x", x & 0xff));'

FIELDS = {
    # miui.os.Build: Global (international) behaviour; e.g. Compass shows China's CTA consent unless this is true.
    'Lmiui/os/Build;->IS_INTERNATIONAL_BUILD:Z': 'true',
    'Lmiui/os/Build;->IS_GLOBAL_BUILD:Z': 'true',
    'Lmiui/os/Build;->IS_STABLE_VERSION:Z': 'true',
    'Lmiui/os/Build;->IS_TABLET:Z': 'false',
    'Lmiui/os/Build;->DEVICE:Ljava/lang/String;': 'android.os.Build.DEVICE',
    'Lmiui/os/Build;->MODEL:Ljava/lang/String;': 'android.os.Build.MODEL',
    'Lmiui/os/Build;->PRODUCT:Ljava/lang/String;': 'android.os.Build.PRODUCT',
    'Lmiui/os/Build;->BRAND:Ljava/lang/String;': 'android.os.Build.BRAND',
    'Lmiui/os/Build;->MANUFACTURER:Ljava/lang/String;': 'android.os.Build.MANUFACTURER',
    'Lmiui/os/Build;->TYPE:Ljava/lang/String;': 'android.os.Build.TYPE',
    'Lmiui/os/Build;->USER:Ljava/lang/String;': 'android.os.Build.USER',
    'Lmiui/os/Build;->FINGERPRINT:Ljava/lang/String;': 'android.os.Build.FINGERPRINT',
    # Scanner calls HARDWARE.matches(..) and CPU_ABI.contains(..): null would be an NPE.
    'Lmiui/os/Build;->HARDWARE:Ljava/lang/String;': 'android.os.Build.HARDWARE',
    'Lmiui/os/Build;->DISPLAY:Ljava/lang/String;': 'android.os.Build.DISPLAY',
    'Lmiui/os/Build;->CPU_ABI:Ljava/lang/String;': 'android.os.Build.SUPPORTED_ABIS[0]',
}

METHODS = {
    # MIUI's own utilities in android.*: plain digests and a default sound, which Android can answer for real.
    'Landroid/os/updater/MD5;->getMd5Digest(Ljava/lang/String;)Ljava/lang/String;':
        'if (p0 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance("MD5")'
        '.digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); ' + HEX_LOOP + ' return h.toString(); }'
        ' catch (java.security.NoSuchAlgorithmException e) { return null; }',
    'Landroid/os/updater/DigestUtils;->get(Ljava/io/InputStream;Ljava/lang/String;)[B':
        'try { java.security.MessageDigest m = java.security.MessageDigest.getInstance(p1); byte[] b = new byte[8192];'
        ' for (int n; (n = p0.read(b)) > 0; ) m.update(b, 0, n); return m.digest(); }'
        ' catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { return null; }',
    'Landroid/os/updater/DigestUtils;->toHexReadable([B)Ljava/lang/String;':
        'if (p0 == null) return null; byte[] d = p0; ' + HEX_LOOP + ' return h.toString();',
    'Landroid/media/ExtraRingtoneManager;->getDefaultSoundActualUri(Landroid/content/Context;I)Landroid/net/Uri;':
        'return android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);',
    # MIUI's coder for its apps' own server requests (Calendar signs them and decrypts the answers with its own keys):
    # SHA-1 as hex, and AES/CBC/PKCS5 with the IV every Xiaomi SDK uses ("0102030405060708"), as Base64.
    'Lmiui/util/CoderUtils;->encodeSHA(Ljava/lang/String;)Ljava/lang/String;':
        'if (p0 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance("SHA-1")'
        '.digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); ' + HEX_LOOP + ' return h.toString(); }'
        ' catch (java.security.NoSuchAlgorithmException e) { return null; }',
    'Lmiui/util/CoderUtils;->base64AesEncode(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;':
        'if (p0 == null || p1 == null) return null; try { javax.crypto.Cipher c = javax.crypto.Cipher.getInstance('
        '"AES/CBC/PKCS5Padding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec('
        'p1.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec('
        '"0102030405060708".getBytes(java.nio.charset.StandardCharsets.UTF_8))); return android.util.Base64.encodeToString('
        'c.doFinal(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP); }'
        ' catch (java.security.GeneralSecurityException | IllegalArgumentException e) { return null; }',
    'Lmiui/util/CoderUtils;->base6AesDecode(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;':
        'if (p0 == null || p1 == null) return null; try { javax.crypto.Cipher c = javax.crypto.Cipher.getInstance('
        '"AES/CBC/PKCS5Padding"); c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec('
        'p1.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec('
        '"0102030405060708".getBytes(java.nio.charset.StandardCharsets.UTF_8))); return new String(c.doFinal('
        'android.util.Base64.decode(p0, android.util.Base64.DEFAULT)), java.nio.charset.StandardCharsets.UTF_8); }'
        ' catch (java.security.GeneralSecurityException | IllegalArgumentException e) { return null; }',
    'Lmiui/util/HashUtils;->getHash(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;':
        'if (p0 == null || p1 == null) return null; try { byte[] d = java.security.MessageDigest.getInstance(p1)'
        '.digest(p0.getBytes(java.nio.charset.StandardCharsets.UTF_8)); ' + HEX_LOOP + ' return h.toString(); }'
        ' catch (java.security.NoSuchAlgorithmException e) { return null; }',
    'Landroid/media/ExtraRingtone;->getRingtoneTitle(Landroid/content/Context;Landroid/net/Uri;Z)Ljava/lang/String;':
        'if (p0 == null || p1 == null) return ""; android.media.Ringtone r = android.media.RingtoneManager.getRingtone('
        'p0, p1); return r == null ? "" : r.getTitle(p0);',
    'Lmiui/os/Build;->checkRegion(Ljava/lang/String;)Z': f'return p0 != null && p0.equalsIgnoreCase({REGION});',
    'Lmiui/os/Build;->getRegion()Ljava/lang/String;': f'return {REGION};',
    'Lmiui/os/Build;->getCustVariant()Ljava/lang/String;': f'return {REGION}.toLowerCase(java.util.Locale.ROOT);',
}

# Fields Xiaomi's libraries only read by reflection, added to a stand-in whenever the app needs that class anyway: miuix
# looks up miui.os.Build.IS_MIUI_LITE_VERSION on every layout pass and caches only an answer, so without it each pass
# throws and logs NoSuchFieldException. Values come from FIELDS, else the type's default (false / 0 / null).
REFLECTED = {
    'Lmiui/os/Build;': ['IS_MIUI_LITE_VERSION:Z'],
}

# Xiaomi framework classes and the Android class each one extends on HyperOS (what the app may cast them to or
# where Android instantiates them), when the app's own use doesn't show it.
SUPERS = {
    'Lmiui/app/backup/FullBackupAgent;': 'Landroid/app/backup/BackupAgent;',
    # Xiaomi's AI speech SDK (com.xiaomi.miai, HyperOS's AI service client): the event payloads the app passes as
    # EventPayloads (the ART verifier rejects the whole calling class otherwise).
    'Lcom/xiaomi/miai/api/SpeechGeneral$RecognizeStreamStarted;': 'Lcom/xiaomi/miai/api/common/EventPayload;',
    'Lcom/xiaomi/miai/api/SpeechGeneral$RecognizeStreamEnded;': 'Lcom/xiaomi/miai/api/common/EventPayload;',
    'Lcom/xiaomi/miai/api/SpeechMiAiSpeechAsr$SpeechRecognize;': 'Lcom/xiaomi/miai/api/common/EventPayload;',
    'Lcom/xiaomi/miai/api/SpeechMiAiSpeechConference$ConferenceServiceInfoUpdate;': 'Lcom/xiaomi/miai/api/common/EventPayload;',
}
