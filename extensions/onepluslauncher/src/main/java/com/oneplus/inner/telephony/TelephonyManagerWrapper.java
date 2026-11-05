package com.oneplus.inner.telephony;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Launcher uses, answering "absent" off ColorOS (no OPPO code). */
public class TelephonyManagerWrapper {
    public static int getCurrentPhoneType(android.telephony.TelephonyManager p0, int p1) {
        return p1;
    }
    public static boolean getDataEnabled(android.telephony.TelephonyManager p0) {
        return false;
    }
    public static java.lang.String getLine1Number(android.telephony.TelephonyManager p0, int p1) {
        return "";
    }
    public static int getSimCount(android.telephony.TelephonyManager p0) {
        return 0;
    }
    public static java.lang.String getSimCountryIso(android.telephony.TelephonyManager p0, int p1) {
        return "";
    }
    public static java.lang.String getSimOperator(android.telephony.TelephonyManager p0, int p1) {
        return "";
    }
    public static java.lang.String getSimOperatorNumeric(android.telephony.TelephonyManager p0, int p1) {
        return "";
    }
    public static int getSubIdForPhoneAccount(android.telephony.TelephonyManager p0, android.telecom.PhoneAccount p1) {
        return 0;
    }
    public static java.lang.String getTelephonyProperty(int p0, java.lang.String p1, java.lang.String p2) {
        return p2;
    }
    public static boolean hasIccCard(android.telephony.TelephonyManager p0, int p1) {
        return false;
    }
    public static boolean isMultiSimEnabled(android.telephony.TelephonyManager p0) {
        return false;
    }
    public static boolean isNetworkRoaming(android.telephony.TelephonyManager p0, int p1) {
        return false;
    }
    public static boolean isVolteAvailable(android.telephony.TelephonyManager p0) {
        return false;
    }
    public static boolean isWifiCallingAvailable(android.telephony.TelephonyManager p0) {
        return false;
    }
    public static void setDataEnabled(android.telephony.TelephonyManager p0, boolean p1) {
    }
}
