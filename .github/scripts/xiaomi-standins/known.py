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
