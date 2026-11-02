package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus My Files uses, answering "absent" off ColorOS (no OPPO code). */
public class SystemProperties {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.colorosmyfiles.ColorOS.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.colorosmyfiles.ColorOS.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.colorosmyfiles.ColorOS.propBoolean(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        return org.ungoogled.colorosmyfiles.ColorOS.propInt(p0, p1);
    }
}
