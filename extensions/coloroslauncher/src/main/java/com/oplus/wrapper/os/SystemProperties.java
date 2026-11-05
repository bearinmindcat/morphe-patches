package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class SystemProperties {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.coloroslauncher.ColorOS.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.coloroslauncher.ColorOS.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.coloroslauncher.ColorOS.propBoolean(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        return org.ungoogled.coloroslauncher.ColorOS.propInt(p0, p1);
    }
    public static long getLong(java.lang.String p0, long p1) {
        return org.ungoogled.coloroslauncher.ColorOS.propLong(p0, p1);
    }
}
