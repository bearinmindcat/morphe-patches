package com.oplus.deepthinker;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public interface IOplusDeepThinkerManager {
    com.oplus.deepthinker.sdk.aidl.proton.deepsleep.TotalPredictResult getDeepSleepTotalPredictResult();
    boolean registerCallback(com.oplus.eventhub.sdk.aidl.IEventCallback p0, com.oplus.eventhub.sdk.aidl.EventRequestConfig p1);
    boolean unregisterCallback(com.oplus.eventhub.sdk.aidl.IEventCallback p0);
}
