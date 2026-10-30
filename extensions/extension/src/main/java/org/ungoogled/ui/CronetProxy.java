package org.ungoogled.ui;

import org.chromium.net.CronetEngine;
import org.chromium.net.Proxy;
import org.chromium.net.ProxyOptions;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Puts the Proxy setting on every Cronet engine Maps builds.
 *
 * Maps fetches its tiles, search and place data through Cronet, normally the
 * copy Play services provides, and Cronet does not keep to the JVM proxy
 * properties: it reads them when an engine starts, then follows the system's
 * own proxy setting (none), and it reaches Google over QUIC, which an HTTP
 * proxy cannot carry at all. Measured on the emulator with the proxy set from
 * process start: 125 KB of direct TCP and 99 KB of QUIC against 30 KB through
 * the proxy. So each engine is given the proxy explicitly, through Cronet's
 * own ProxyOptions, with no direct fallback -- if the proxy goes away, Maps
 * stops loading instead of quietly going direct -- and with QUIC off.
 *
 * Called first thing in CronetEngine.Builder.buildExperimental(), which every
 * engine build goes through. Kept apart from Shapes so that nothing loaded at
 * process start refers to Cronet's classes.
 */
public final class CronetProxy {
    private CronetProxy() {}

    private static final Executor CALLBACKS = Executors.newSingleThreadExecutor();

    static final String UNSUPPORTED = "Maps can't apply the required proxy — network engine blocked";

    /** False once an engine could not take the proxy (a Cronet without ProxyOptions). */
    static volatile boolean supported = true;

    public static void onBuild(Object builder) {
        String hp = Shapes.proxyEffective();
        if (hp.isEmpty()) {
            if (Shapes.proxyRequired()) throw new IllegalStateException("Proxy is enabled but its configuration is invalid");
            return;
        }
        int colon = hp.lastIndexOf(':');
        if (colon <= 0) throw new IllegalStateException("Invalid proxy endpoint");
        String host = hp.substring(0, colon).trim();
        int port;
        try {
            port = Integer.parseInt(hp.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid proxy port", e);
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalStateException("Invalid proxy endpoint");
        try {
            CronetEngine.Builder b = (CronetEngine.Builder) builder;
            b.enableQuic(false);
            Proxy proxy = Proxy.createHttpProxy(Proxy.SCHEME_HTTP, host, port, CALLBACKS, new Connect());
            b.setProxyOptions(ProxyOptions.fromProxyList(Collections.singletonList(proxy),
                    ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT));
        } catch (RuntimeException | LinkageError t) {
            // Do not let the injected buildExperimental hook continue with an unproxied engine.
            supported = false;
            Shapes.warnProxy(UNSUPPORTED);
            throw new IllegalStateException(UNSUPPORTED, t);
        }
    }

    /** Sends each CONNECT as is and uses the tunnel whatever the proxy answers; Cronet itself rejects a failed one. */
    static final class Connect extends Proxy.HttpConnectCallback {
        @Override public void onBeforeRequest(Request request) {
            request.proceed(Collections.emptyList());
        }

        @Override public int onResponseReceived(List responseHeaders, int statusCode) {
            return RESPONSE_ACTION_PROCEED;
        }
    }
}
