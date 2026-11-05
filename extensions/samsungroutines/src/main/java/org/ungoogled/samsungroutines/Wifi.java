package org.ungoogled.samsungroutines;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * The "Wi-Fi network" condition's look at the current network. As SystemUI the app always sees its name; any other app
 * gets "<unknown ssid>" without location (from the engine: location all the time) or with location off, and the
 * condition never held. The user is asked for it once (see Access.needLocation).
 */
public final class Wifi {
    private Wifi() {
    }

    public static WifiInfo connectionInfo(WifiManager wifi) {
        Context c = EngineHost.app;
        if (c != null) Access.needLocation(c);
        return wifi.getConnectionInfo();
    }
}
