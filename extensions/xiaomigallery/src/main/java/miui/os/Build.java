package miui.os;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Gallery uses, answering "absent" off HyperOS (no Xiaomi code). */
public class Build {
    public static java.lang.String DEVICE = android.os.Build.DEVICE;
    public static boolean IS_ALPHA_BUILD;
    public static boolean IS_DEVELOPMENT_VERSION;
    public static boolean IS_GLOBAL_BUILD = true;
    public static boolean IS_INTERNATIONAL_BUILD = true;
    public static boolean IS_MIPAD;
    public static boolean IS_MIUI_LITE_VERSION;
    public static boolean IS_STABLE_VERSION = true;
    public static boolean IS_TABLET = false;
    public static java.lang.String getRegion() {
        return java.util.Locale.getDefault().getCountry();
    }
}
