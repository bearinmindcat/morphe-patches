package com.lge.systemservice.core;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses, answering "absent" off LG's ROM (no LG code). */
public class LeccpManager {
    public void discoverService(int p0) {
    }
    public com.lge.systemservice.core.LeccpInfo.Card getCard(java.lang.String p0) {
        return null;
    }
    public java.util.List getCards(int[] p0) {
        return new java.util.ArrayList<>();
    }
    public void register(com.lge.systemservice.core.LeccpManager.LeccpListener p0) {
    }
    public java.lang.String requestAction(java.lang.String p0, com.lge.systemservice.core.LeccpInfo.ActionRequest p1) {
        return "";
    }
    public void startActiveWifiDiscovery(int[] p0) {
    }
    public void stopActiveWifiDiscovery() {
    }
    public void stopDiscovery() {
    }
    public void unregister(com.lge.systemservice.core.LeccpManager.LeccpListener p0) {
    }
    public static class LeccpListener {
        public LeccpListener() {
        }
        public void onCardRemoved(java.lang.String p0) {
        }
        public void onStatusChanged(com.lge.systemservice.core.LeccpInfo.Status p0) {
        }
    }
}
