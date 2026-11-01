package miui.os;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Screen Recorder uses, answering "absent" off HyperOS (no Xiaomi code). */
public class Build {
    public static boolean IS_INTERNATIONAL_BUILD = true;
    public static boolean IS_MIUI_LITE_VERSION;
    public static boolean IS_STABLE_VERSION = true;
    public static boolean IS_TABLET = false;
    public static boolean checkRegion(java.lang.String p0) {
        return p0 != null && p0.equalsIgnoreCase(java.util.Locale.getDefault().getCountry());
    }
}
