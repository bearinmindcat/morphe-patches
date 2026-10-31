package org.ungoogled.samsungcontacts;

import android.util.Log;

/**
 * Samsung Contacts's TelephonyManager, SubscriptionManager and SmsManager calls. On One UI it is a preloaded app with privileged phone
 * permissions; elsewhere the user grants the phone permission later (or never), and device identifiers (IMSI, SIM
 * serial, ...) stay out of reach of every ordinary app: a refused call threw SecurityException, which crashed the
 * app. A refused call answers "nothing" (null, false, 0, an empty list, an invalid subscription) instead.
 */
public final class Telephony {
    private static final String TAG = "UA";

    private Telephony() {
    }

    private static void refused(String call, SecurityException e) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "telephony: " + call + " refused: " + e.getMessage());
    }

    public static android.telephony.SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(android.telephony.SubscriptionManager o, int p0) {
        try {
            return o.getActiveSubscriptionInfoForSimSlotIndex(p0);
        } catch (SecurityException e) {
            refused("getActiveSubscriptionInfoForSimSlotIndex", e);
            return null;
        }
    }

    public static android.telephony.SubscriptionInfo getActiveSubscriptionInfo(android.telephony.SubscriptionManager o, int p0) {
        try {
            return o.getActiveSubscriptionInfo(p0);
        } catch (SecurityException e) {
            refused("getActiveSubscriptionInfo", e);
            return null;
        }
    }

    public static java.util.List getActiveSubscriptionInfoList(android.telephony.SubscriptionManager o) {
        try {
            return o.getActiveSubscriptionInfoList();
        } catch (SecurityException e) {
            refused("getActiveSubscriptionInfoList", e);
            return java.util.Collections.emptyList();
        }
    }

    public static java.util.List getCompleteActiveSubscriptionInfoList(android.telephony.SubscriptionManager o) {
        try {
            return o.getCompleteActiveSubscriptionInfoList();
        } catch (SecurityException e) {
            refused("getCompleteActiveSubscriptionInfoList", e);
            return java.util.Collections.emptyList();
        }
    }

    public static java.lang.String getPhoneNumber(android.telephony.SubscriptionManager o, int p0) {
        try {
            return o.getPhoneNumber(p0);
        } catch (SecurityException e) {
            refused("getPhoneNumber", e);
            return null;
        }
    }

    public static android.telephony.TelephonyManager createForSubscriptionId(android.telephony.TelephonyManager o, int p0) {
        try {
            return o.createForSubscriptionId(p0);
        } catch (SecurityException e) {
            refused("createForSubscriptionId", e);
            return null;
        }
    }

    public static java.lang.String getNetworkCountryIso(android.telephony.TelephonyManager o) {
        try {
            return o.getNetworkCountryIso();
        } catch (SecurityException e) {
            refused("getNetworkCountryIso", e);
            return null;
        }
    }

    public static java.lang.String getNetworkOperator(android.telephony.TelephonyManager o) {
        try {
            return o.getNetworkOperator();
        } catch (SecurityException e) {
            refused("getNetworkOperator", e);
            return null;
        }
    }

    public static java.lang.String getNetworkOperatorName(android.telephony.TelephonyManager o) {
        try {
            return o.getNetworkOperatorName();
        } catch (SecurityException e) {
            refused("getNetworkOperatorName", e);
            return null;
        }
    }

    public static int getNetworkType(android.telephony.TelephonyManager o) {
        try {
            return o.getNetworkType();
        } catch (SecurityException e) {
            refused("getNetworkType", e);
            return 0;
        }
    }

    public static android.telephony.ServiceState getServiceState(android.telephony.TelephonyManager o) {
        try {
            return o.getServiceState();
        } catch (SecurityException e) {
            refused("getServiceState", e);
            return null;
        }
    }

    public static java.lang.String getSimCountryIso(android.telephony.TelephonyManager o) {
        try {
            return o.getSimCountryIso();
        } catch (SecurityException e) {
            refused("getSimCountryIso", e);
            return null;
        }
    }

    public static java.lang.String getSimOperator(android.telephony.TelephonyManager o) {
        try {
            return o.getSimOperator();
        } catch (SecurityException e) {
            refused("getSimOperator", e);
            return null;
        }
    }

    public static java.lang.String getSimOperatorName(android.telephony.TelephonyManager o) {
        try {
            return o.getSimOperatorName();
        } catch (SecurityException e) {
            refused("getSimOperatorName", e);
            return null;
        }
    }

    public static int getSimState(android.telephony.TelephonyManager o) {
        try {
            return o.getSimState();
        } catch (SecurityException e) {
            refused("getSimState", e);
            return 0;
        }
    }

    public static int getSimState(android.telephony.TelephonyManager o, int p0) {
        try {
            return o.getSimState(p0);
        } catch (SecurityException e) {
            refused("getSimState", e);
            return 0;
        }
    }

    public static int getSubscriptionId(android.telephony.TelephonyManager o) {
        try {
            return o.getSubscriptionId();
        } catch (SecurityException e) {
            refused("getSubscriptionId", e);
            return -1;
        }
    }

    public static int getSubscriptionId(android.telephony.TelephonyManager o, android.telecom.PhoneAccountHandle p0) {
        try {
            return o.getSubscriptionId(p0);
        } catch (SecurityException e) {
            refused("getSubscriptionId", e);
            return -1;
        }
    }

    public static int getVoiceNetworkType(android.telephony.TelephonyManager o) {
        try {
            return o.getVoiceNetworkType();
        } catch (SecurityException e) {
            refused("getVoiceNetworkType", e);
            return 0;
        }
    }

    public static boolean hasIccCard(android.telephony.TelephonyManager o) {
        try {
            return o.hasIccCard();
        } catch (SecurityException e) {
            refused("hasIccCard", e);
            return false;
        }
    }

    public static boolean isDataEnabledForReason(android.telephony.TelephonyManager o, int p0) {
        try {
            return o.isDataEnabledForReason(p0);
        } catch (SecurityException e) {
            refused("isDataEnabledForReason", e);
            return false;
        }
    }

    public static boolean isDataEnabled(android.telephony.TelephonyManager o) {
        try {
            return o.isDataEnabled();
        } catch (SecurityException e) {
            refused("isDataEnabled", e);
            return false;
        }
    }

    public static boolean isDataRoamingEnabled(android.telephony.TelephonyManager o) {
        try {
            return o.isDataRoamingEnabled();
        } catch (SecurityException e) {
            refused("isDataRoamingEnabled", e);
            return false;
        }
    }

    public static boolean isDeviceVoiceCapable(android.telephony.TelephonyManager o) {
        try {
            return o.isDeviceVoiceCapable();
        } catch (SecurityException e) {
            refused("isDeviceVoiceCapable", e);
            return false;
        }
    }

    public static boolean isEmergencyNumber(android.telephony.TelephonyManager o, java.lang.String p0) {
        try {
            return o.isEmergencyNumber(p0);
        } catch (SecurityException e) {
            refused("isEmergencyNumber", e);
            return false;
        }
    }

    public static boolean isNetworkRoaming(android.telephony.TelephonyManager o) {
        try {
            return o.isNetworkRoaming();
        } catch (SecurityException e) {
            refused("isNetworkRoaming", e);
            return false;
        }
    }

    public static boolean isSmsCapable(android.telephony.TelephonyManager o) {
        try {
            return o.isSmsCapable();
        } catch (SecurityException e) {
            refused("isSmsCapable", e);
            return false;
        }
    }

    public static boolean isVoiceCapable(android.telephony.TelephonyManager o) {
        try {
            return o.isVoiceCapable();
        } catch (SecurityException e) {
            refused("isVoiceCapable", e);
            return false;
        }
    }

    public static void registerTelephonyCallback(android.telephony.TelephonyManager o, java.util.concurrent.Executor p0, android.telephony.TelephonyCallback p1) {
        try {
            o.registerTelephonyCallback(p0, p1);
        } catch (SecurityException e) {
            refused("registerTelephonyCallback", e);
            // Skipped.
        }
    }

    public static void setDataEnabled(android.telephony.TelephonyManager o, boolean p0) {
        try {
            o.setDataEnabled(p0);
        } catch (SecurityException e) {
            refused("setDataEnabled", e);
            // Skipped.
        }
    }

    public static void unregisterTelephonyCallback(android.telephony.TelephonyManager o, android.telephony.TelephonyCallback p0) {
        try {
            o.unregisterTelephonyCallback(p0);
        } catch (SecurityException e) {
            refused("unregisterTelephonyCallback", e);
            // Skipped.
        }
    }
}
