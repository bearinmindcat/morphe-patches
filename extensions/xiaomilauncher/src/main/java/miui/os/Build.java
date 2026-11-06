package miui.os;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides): the generated one plus IS_DEV_VERSION, which the launcher reads by
 * reflection in a class initializer and unboxes without a check (a stable build here). Names only, no Xiaomi code.
 */
public class Build {
    public static java.lang.String BRAND = android.os.Build.BRAND;
    public static java.lang.String DEVICE = android.os.Build.DEVICE;
    public static boolean IS_ALPHA_BUILD;
    public static boolean IS_CM_CUSTOMIZATION;
    public static boolean IS_DEMO_BUILD;
    public static boolean IS_DEVELOPMENT_VERSION;
    public static boolean IS_DEV_VERSION;
    public static boolean IS_GLOBAL_BUILD = true;
    public static boolean IS_HONGMI;
    public static boolean IS_INTERNATIONAL_BUILD = true;
    public static boolean IS_MIPAD;
    public static boolean IS_MIUI_LITE_VERSION;
    public static boolean IS_STABLE_VERSION = true;
    public static boolean IS_TABLET = false;
    public static java.lang.String MODEL = android.os.Build.MODEL;
    public static java.lang.String PRODUCT = android.os.Build.PRODUCT;
    public static int TOTAL_RAM;
    public static java.lang.String getRegion() {
        return java.util.Locale.getDefault().getCountry();
    }
}
