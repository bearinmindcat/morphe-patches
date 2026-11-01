package com.hihonor.android.os;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Files uses, answering "absent" off MagicOS (no Honor code). */
public class SystemPropertiesEx {
    public static java.lang.String get(java.lang.String p0) {
        return org.ungoogled.honorfiles.Magic.prop(p0, "");
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        return org.ungoogled.honorfiles.Magic.prop(p0, p1);
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        return org.ungoogled.honorfiles.Magic.propBoolean(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        return org.ungoogled.honorfiles.Magic.propInt(p0, p1);
    }
}
