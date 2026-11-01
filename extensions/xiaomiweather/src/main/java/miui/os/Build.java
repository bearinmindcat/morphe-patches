package miui.os;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Weather uses, answering "absent" off HyperOS (no Xiaomi code). */
public class Build {
    public static boolean IS_ALPHA_BUILD;
    public static boolean IS_DEVELOPMENT_VERSION;
    public static boolean IS_INTERNATIONAL_BUILD = true;
    public static boolean IS_STABLE_VERSION = true;
    public static boolean IS_TABLET = false;
    public static boolean checkRegion(java.lang.String p0) {
        return p0 != null && p0.equalsIgnoreCase(java.util.Locale.getDefault().getCountry());
    }
    public static java.lang.String getRegion() {
        return java.util.Locale.getDefault().getCountry();
    }
}
