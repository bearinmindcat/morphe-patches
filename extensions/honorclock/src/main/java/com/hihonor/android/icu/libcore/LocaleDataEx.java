package com.hihonor.android.icu.libcore;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Clock uses, answering "absent" off MagicOS (no Honor code). */
public class LocaleDataEx {
    public LocaleDataEx() {
    }
    public static com.hihonor.android.icu.libcore.LocaleDataEx get(java.util.Locale p0) {
        return new com.hihonor.android.icu.libcore.LocaleDataEx();
    }
    public java.lang.String[] getShortStandAloneWeekdayNames() {
        return android.icu.text.DateFormatSymbols.getInstance(java.util.Locale.getDefault()).getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.SHORT);
    }
    public java.lang.String getTimeFormat_Hm() {
        return android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "Hm");
    }
    public java.lang.String getTimeFormat_hm() {
        return android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "hm");
    }
}
