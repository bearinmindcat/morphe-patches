package org.ungoogled.ui;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

final class LibraryNetwork {
    private LibraryNetwork() {}

    static HttpURLConnection open(URL url) throws IOException {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null ||
                url.getPort() != -1 && url.getPort() != 443) throw new IOException("HTTPS without credentials is required");
        String setting = Shapes.proxyEffective();
        HttpURLConnection connection;
        if (setting.isEmpty()) connection = (HttpURLConnection) url.openConnection();
        else {
            int colon = setting.lastIndexOf(':');
            if (colon < 1) throw new IOException("Invalid proxy configuration");
            try {
                Proxy proxy = new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(setting.substring(0, colon), Integer.parseInt(setting.substring(colon + 1))));
                connection = (HttpURLConnection) url.openConnection(proxy);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid proxy configuration", e);
            }
        }
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
