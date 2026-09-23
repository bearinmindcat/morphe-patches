package org.chromium.net;

import java.util.List;
import java.util.concurrent.Executor;

/** Stub of Cronet's org.chromium.net.Proxy; the real class ships inside Maps. */
public final class Proxy {
    public static final int SCHEME_HTTP = 0;
    public static final int SCHEME_HTTPS = 1;

    private Proxy() {}

    public static Proxy createHttpProxy(int scheme, String host, int port, Executor executor, HttpConnectCallback callback) {
        throw new UnsupportedOperationException("stub");
    }

    public abstract static class HttpConnectCallback {
        public static final int RESPONSE_ACTION_CLOSE = 0;
        public static final int RESPONSE_ACTION_PROCEED = 1;

        public abstract static class Request implements AutoCloseable {
            @Override public abstract void close();
            public abstract void proceed(List extraHeaders);
        }

        public abstract void onBeforeRequest(Request request);
        public abstract int onResponseReceived(List responseHeaders, int statusCode);
    }
}
