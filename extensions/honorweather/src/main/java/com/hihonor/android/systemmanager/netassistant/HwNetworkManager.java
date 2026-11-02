package com.hihonor.android.systemmanager.netassistant;

/**
 * Generated stand-in (.github/scripts/honor-standins), answered by hand: no MagicOS per-app network policy off MagicOS,
 * so a policy manager that restricts no app (Weather reads it before each refresh without a null check).
 */
public class HwNetworkManager {
    public static com.hihonor.android.systemmanager.netassistant.IHwNetworkPolicyManager getHwNetworkPolicyManager(android.content.Context p0) {
        return uid -> 0;
    }
}
