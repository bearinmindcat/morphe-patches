package com.oplus.wrapper.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Contacts uses, answering "absent" off ColorOS (no OPPO code). */
public class SystemProperties {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.coloroscontacts.ColorOS.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.coloroscontacts.ColorOS.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.coloroscontacts.ColorOS.propBoolean(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        return org.ungoogled.coloroscontacts.ColorOS.propInt(p0, p1);
    }
}
