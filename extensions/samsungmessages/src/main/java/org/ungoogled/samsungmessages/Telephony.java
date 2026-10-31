package org.ungoogled.samsungmessages;

import android.util.Log;

/**
 * Samsung Messages's TelephonyManager, SubscriptionManager and SmsManager calls. On One UI it is a preloaded app with privileged phone
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

    public static android.telephony.SmsManager createForSubscriptionId(android.telephony.SmsManager o, int p0) {
        try {
            return o.createForSubscriptionId(p0);
        } catch (SecurityException e) {
            refused("createForSubscriptionId", e);
            return null;
        }
    }

    public static java.util.ArrayList divideMessage(android.telephony.SmsManager o, java.lang.String p0) {
        try {
            return o.divideMessage(p0);
        } catch (SecurityException e) {
            refused("divideMessage", e);
            return null;
        }
    }

    public static void downloadMultimediaMessage(android.telephony.SmsManager o, android.content.Context p0, java.lang.String p1, android.net.Uri p2, android.os.Bundle p3, android.app.PendingIntent p4) {
        try {
            o.downloadMultimediaMessage(p0, p1, p2, p3, p4);
        } catch (SecurityException e) {
            refused("downloadMultimediaMessage", e);
            // Skipped.
        }
    }

    public static android.os.Bundle getCarrierConfigValues(android.telephony.SmsManager o) {
        try {
            return o.getCarrierConfigValues();
        } catch (SecurityException e) {
            refused("getCarrierConfigValues", e);
            return null;
        }
    }

    public static java.lang.String getSmscAddress(android.telephony.SmsManager o) {
        try {
            return o.getSmscAddress();
        } catch (SecurityException e) {
            refused("getSmscAddress", e);
            return null;
        }
    }

    public static void sendMultimediaMessage(android.telephony.SmsManager o, android.content.Context p0, android.net.Uri p1, java.lang.String p2, android.os.Bundle p3, android.app.PendingIntent p4) {
        try {
            o.sendMultimediaMessage(p0, p1, p2, p3, p4);
        } catch (SecurityException e) {
            refused("sendMultimediaMessage", e);
            // Skipped.
        }
    }

    public static void sendMultipartTextMessage(android.telephony.SmsManager o, java.lang.String p0, java.lang.String p1, java.util.ArrayList p2, java.util.ArrayList p3, java.util.ArrayList p4) {
        try {
            o.sendMultipartTextMessage(p0, p1, p2, p3, p4);
        } catch (SecurityException e) {
            refused("sendMultipartTextMessage", e);
            // Skipped.
        }
    }

    public static void addOnSubscriptionsChangedListener(android.telephony.SubscriptionManager o, android.telephony.SubscriptionManager.OnSubscriptionsChangedListener p0) {
        try {
            o.addOnSubscriptionsChangedListener(p0);
        } catch (SecurityException e) {
            refused("addOnSubscriptionsChangedListener", e);
            // Skipped.
        }
    }

    public static int getActiveSubscriptionInfoCount(android.telephony.SubscriptionManager o) {
        try {
            return o.getActiveSubscriptionInfoCount();
        } catch (SecurityException e) {
            refused("getActiveSubscriptionInfoCount", e);
            return 0;
        }
    }

    public static int getActiveSubscriptionInfoCountMax(android.telephony.SubscriptionManager o) {
        try {
            return o.getActiveSubscriptionInfoCountMax();
        } catch (SecurityException e) {
            refused("getActiveSubscriptionInfoCountMax", e);
            return 0;
        }
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

    public static int getActiveModemCount(android.telephony.TelephonyManager o) {
        try {
            return o.getActiveModemCount();
        } catch (SecurityException e) {
            refused("getActiveModemCount", e);
            return 0;
        }
    }

    public static int getCallState(android.telephony.TelephonyManager o) {
        try {
            return o.getCallState();
        } catch (SecurityException e) {
            refused("getCallState", e);
            return 0;
        }
    }

    public static int getDataNetworkType(android.telephony.TelephonyManager o) {
        try {
            return o.getDataNetworkType();
        } catch (SecurityException e) {
            refused("getDataNetworkType", e);
            return 0;
        }
    }

    public static java.lang.String getDeviceId(android.telephony.TelephonyManager o, int p0) {
        try {
            return o.getDeviceId(p0);
        } catch (SecurityException e) {
            refused("getDeviceId", e);
            return null;
        }
    }

    public static java.lang.String getGroupIdLevel1(android.telephony.TelephonyManager o) {
        try {
            return o.getGroupIdLevel1();
        } catch (SecurityException e) {
            refused("getGroupIdLevel1", e);
            return null;
        }
    }

    public static java.lang.String getLine1Number(android.telephony.TelephonyManager o) {
        try {
            return o.getLine1Number();
        } catch (SecurityException e) {
            refused("getLine1Number", e);
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

    public static int getPhoneCount(android.telephony.TelephonyManager o) {
        try {
            return o.getPhoneCount();
        } catch (SecurityException e) {
            refused("getPhoneCount", e);
            return 0;
        }
    }

    public static int getPhoneType(android.telephony.TelephonyManager o) {
        try {
            return o.getPhoneType();
        } catch (SecurityException e) {
            refused("getPhoneType", e);
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

    public static int getSimCarrierId(android.telephony.TelephonyManager o) {
        try {
            return o.getSimCarrierId();
        } catch (SecurityException e) {
            refused("getSimCarrierId", e);
            return 0;
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

    public static java.lang.String getSimSerialNumber(android.telephony.TelephonyManager o) {
        try {
            return o.getSimSerialNumber();
        } catch (SecurityException e) {
            refused("getSimSerialNumber", e);
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

    public static java.lang.String getSubscriberId(android.telephony.TelephonyManager o) {
        try {
            return o.getSubscriberId();
        } catch (SecurityException e) {
            refused("getSubscriberId", e);
            return null;
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

    public static void listen(android.telephony.TelephonyManager o, android.telephony.PhoneStateListener p0, int p1) {
        try {
            o.listen(p0, p1);
        } catch (SecurityException e) {
            refused("listen", e);
            // Skipped.
        }
    }

    public static void registerTelephonyCallback(android.telephony.TelephonyManager o, int p0, java.util.concurrent.Executor p1, android.telephony.TelephonyCallback p2) {
        try {
            o.registerTelephonyCallback(p0, p1, p2);
        } catch (SecurityException e) {
            refused("registerTelephonyCallback", e);
            // Skipped.
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
