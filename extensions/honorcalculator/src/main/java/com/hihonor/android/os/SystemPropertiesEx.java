package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Calculator uses, answering "absent" off MagicOS (no Honor code). */
public class SystemPropertiesEx {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.honorcalculator.Magic.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.honorcalculator.Magic.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.honorcalculator.Magic.propBoolean(p0, p1);
    }
}
