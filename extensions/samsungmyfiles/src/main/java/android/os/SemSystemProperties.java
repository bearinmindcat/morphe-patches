package android.os;

/**
 * Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung My Files uses, answering "absent"
 * off One UI (no Samsung code), except the SD card slot flag (see getInt).
 */
public class SemSystemProperties {
    public static java.lang.String get(java.lang.String p0) {
        return "";
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return p1;
    }
    public static java.lang.String getCountryCode() {
        return "";
    }
    public static int getInt(java.lang.String p0, int p1) {
        // One UI's "this phone has an SD card slot": without it My Files never shows an SD card. Elsewhere there is no
        // such flag, so any phone may have one; the card shows only while Android has one mounted.
        if ("storage.support.sdcard".equals(p0)) return 1;
        return p1;
    }
    public static java.lang.String getSalesCode() {
        return "";
    }
}
