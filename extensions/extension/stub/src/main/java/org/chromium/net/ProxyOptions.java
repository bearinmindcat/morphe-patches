package org.chromium.net;

import java.util.List;

/** Stub of Cronet's org.chromium.net.ProxyOptions; the real class ships inside Maps. */
public final class ProxyOptions {
    public static final int ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT = 0;
    public static final int ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT = 1;

    private ProxyOptions() {}

    public static ProxyOptions fromProxyList(List<Proxy> proxies, int allProxiesFailedBehavior) {
        throw new UnsupportedOperationException("stub");
    }
}
