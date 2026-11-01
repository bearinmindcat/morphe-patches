package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Compass uses, answering "absent" off MagicOS (no Honor code). */
public class SystemPropertiesEx {
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.honorcompass.Magic.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.honorcompass.Magic.propBoolean(p0, p1);
    }
}
