package com.hihonor.android.os;

/**
 * Stand-in (written by hand from the generated one): MagicOS's wrapper of Android's system properties, answering with
 * the phone's own values (Magic.prop), so MagicOS-only ones are empty and Honor Home takes its own defaults. One more
 * answer: the display density (ro.sf.lcd_density), which Honor phones always set and Honor Home divides by to scale its
 * dock; a phone without it (the emulator only has qemu.sf.lcd_density) gets the display's own density. And
 * ro.feature.launcher.long_click_enter_edit_mode is on (an Honor product option): a long press on the home screen opens
 * its edit mode, as on other launchers, besides MagicOS's pinch.
 */
public class SystemPropertiesEx {
    private static String answer(java.lang.String key, java.lang.String value) {
        if (value.isEmpty() && "ro.sf.lcd_density".equals(key)) {
            return Integer.toString(android.util.DisplayMetrics.DENSITY_DEVICE_STABLE);
        }
        return value;
    }
    public static java.lang.String get(java.lang.String p0) {
        return answer(p0, org.ungoogled.honorhome.Magic.prop(p0, ""));
    }
    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        java.lang.String value = answer(p0, org.ungoogled.honorhome.Magic.prop(p0, ""));
        return value.isEmpty() ? p1 : value;
    }
    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        if ("ro.feature.launcher.long_click_enter_edit_mode".equals(p0)) return true;
        return org.ungoogled.honorhome.Magic.propBoolean(p0, p1);
    }
    public static int getInt(java.lang.String p0, int p1) {
        java.lang.String value = get(p0, null);
        if (value == null) return p1;
        try {
            return Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return p1;
        }
    }
}
