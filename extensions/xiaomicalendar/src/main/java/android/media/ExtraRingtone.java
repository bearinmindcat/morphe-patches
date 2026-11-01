package android.media;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Calendar uses, answering "absent" off HyperOS (no Xiaomi code). */
public class ExtraRingtone {
    public static java.lang.String getRingtoneTitle(android.content.Context p0, android.net.Uri p1, boolean p2) {
        if (p0 == null || p1 == null) return ""; android.media.Ringtone r = android.media.RingtoneManager.getRingtone(p0, p1); return r == null ? "" : r.getTitle(p0);
    }
}
