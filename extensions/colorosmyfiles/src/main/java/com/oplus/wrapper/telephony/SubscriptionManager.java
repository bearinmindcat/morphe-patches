package com.oplus.wrapper.telephony;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus My Files uses, answering "absent" off ColorOS (no OPPO code). */
public class SubscriptionManager {
    public static int[] getSubId(int p0) {
        int s = android.telephony.SubscriptionManager.getSubscriptionId(p0); return s == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID ? null : new int[] { s };
    }
}
