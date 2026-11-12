package org.ungoogled.samsungemail;

import android.telephony.TelephonyManager;

/**
 * The phone's identifiers Email reads for Exchange ActiveSync: the IMEI/MEID for its device ID and the "device
 * information" it reports to the server (IMEI, and the phone number or else the SIM's IMSI). Only a preloaded app may
 * read them; elsewhere each call threw SecurityException, which cancelled the whole report (and with it the account's
 * provisioning on Exchange 2010 and later). They are answered "unknown" without asking: the report then carries model,
 * OS and language only, and the device ID is Email's own random one (DeviceIds).
 */
public final class Telephony {
    private Telephony() {
    }

    public static String getPrimaryImei(TelephonyManager tm) {
        return null;
    }

    public static String getDeviceId(TelephonyManager tm) {
        return null;
    }

    public static String getDeviceId(TelephonyManager tm, int slot) {
        return null;
    }

    public static String getImei(TelephonyManager tm) {
        return null;
    }

    public static String getImei(TelephonyManager tm, int slot) {
        return null;
    }

    public static String getMeid(TelephonyManager tm) {
        return null;
    }

    public static String getMeid(TelephonyManager tm, int slot) {
        return null;
    }

    public static String getSubscriberId(TelephonyManager tm) {
        return null;
    }

    public static String getSimSerialNumber(TelephonyManager tm) {
        return null;
    }

    public static String getLine1Number(TelephonyManager tm) {
        return null;
    }
}
