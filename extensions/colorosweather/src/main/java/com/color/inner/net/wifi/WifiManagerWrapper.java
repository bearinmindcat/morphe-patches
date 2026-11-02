package com.color.inner.net.wifi;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Weather uses, answering "absent" off ColorOS (no OPPO code). */
public class WifiManagerWrapper {
    public static void connect(android.net.wifi.WifiManager p0, int p1, com.color.inner.net.wifi.WifiManagerWrapper.ActionListenerWrapper p2) {
    }
    public static void connect(android.net.wifi.WifiManager p0, android.net.wifi.WifiConfiguration p1, com.color.inner.net.wifi.WifiManagerWrapper.ActionListenerWrapper p2) {
    }
    public static void disableDualSta(android.net.wifi.WifiManager p0) {
    }
    public static int enableDualSta(android.net.wifi.WifiManager p0) {
        return 0;
    }
    public static int enableDualStaByForce(android.net.wifi.WifiManager p0, boolean p1) {
        return 0;
    }
    public static void forget(android.net.wifi.WifiManager p0, int p1, com.color.inner.net.wifi.WifiManagerWrapper.ActionListenerWrapper p2) {
    }
    public static java.lang.String[] getAllDualStaApps(android.net.wifi.WifiManager p0) {
        return new java.lang.String[0];
    }
    public static java.lang.String[] getAllSlaAcceleratedApps(android.net.wifi.WifiManager p0) {
        return new java.lang.String[0];
    }
    public static java.lang.String[] getAllSlaAppsAndStates(android.net.wifi.WifiManager p0) {
        return new java.lang.String[0];
    }
    public static android.net.wifi.WifiInfo getOppoSta2ConnectionInfo(android.net.wifi.WifiManager p0) {
        return null;
    }
    public static boolean getSlaAppState(android.net.wifi.WifiManager p0, java.lang.String p1) {
        return false;
    }
    public static android.net.wifi.WifiConfiguration getWifiApConfiguration(android.net.wifi.WifiManager p0) {
        return null;
    }
    public static int getWifiApState(android.net.wifi.WifiManager p0) {
        return 0;
    }
    public static android.net.wifi.WifiConfiguration getWifiSharingConfiguration(android.net.wifi.WifiManager p0) {
        return null;
    }
    public static boolean isCertificateExist(android.net.wifi.WifiManager p0, java.lang.String p1) {
        return false;
    }
    public static boolean isCertificateExpired(android.net.wifi.WifiManager p0, java.lang.String p1) {
        return false;
    }
    public static boolean isCertificatePreInstalled(android.net.wifi.WifiManager p0, java.lang.String p1) {
        return false;
    }
    public static boolean isDualBandSupported(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static boolean isDualStaSupported(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static boolean isMptcpSupported(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static boolean isPasspointFeatureSupport(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static boolean isSlaSupported(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static boolean isWIFI6Supported(android.net.wifi.WifiManager p0) {
        return false;
    }
    public static java.util.List passpointANQPScanResults(android.net.wifi.WifiManager p0, java.util.List p1) {
        return new java.util.ArrayList<>();
    }
    public static boolean setPasspointCertifiedState(android.net.wifi.WifiManager p0, java.lang.String p1) {
        return false;
    }
    public static boolean setSlaAppState(android.net.wifi.WifiManager p0, java.lang.String p1, boolean p2) {
        return false;
    }
    public static boolean setWifiApConfiguration(android.net.wifi.WifiManager p0, android.net.wifi.WifiConfiguration p1) {
        return false;
    }
    public static void setWifiSharingConfiguration(android.net.wifi.WifiManager p0, android.net.wifi.WifiConfiguration p1) {
    }
    public interface ActionListenerWrapper {
    }
}
