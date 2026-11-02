package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Compass uses, answering "absent" off ColorOS (no OPPO code). */
public class SystemProperties {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.coloroscompass.ColorOS.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.coloroscompass.ColorOS.prop(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        return org.ungoogled.coloroscompass.ColorOS.propInt(p0, p1);
    }
}
