package org.ungoogled.xiaominotes.micloud;

import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The calls Xiaomi's own notes website (i.mi.com/note) makes, made the same way with the session the user signed in to
 * in our WebView: the page's cookies, the WebView's user agent, `ts` on every GET and the session's serviceToken in
 * every POST form. Nothing else is sent: no device id, no account data beyond the session cookies.
 */
final class MiCloudApi {
    static final String TAG = "UMiCloud";

    /** The session is gone (HTTP 401/403): sign in again. */
    static final class SessionExpired extends IOException {
        SessionExpired(String message) {
            super(message);
        }
    }

    /** The site answered with an error code. */
    static final class ApiError extends IOException {
        final int code;

        ApiError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final int MAX_RETRIES = 3;
    private static final long MAX_DOWNLOAD = 64L << 20;

    final String base;
    private final String userAgent;
    private final boolean allowHttp;
    private final boolean keepCookies;
    private String cookie;

    MiCloudApi(String base, String cookie, String userAgent, boolean allowHttp, boolean keepCookies) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.cookie = cookie;
        this.userAgent = userAgent;
        this.allowHttp = allowHttp;
        this.keepCookies = keepCookies;
        if (!allowHttp && !this.base.startsWith("https://")) throw new IllegalArgumentException("not https: " + base);
    }

    static String cookieValue(String cookies, String name) {
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
        }
        return null;
    }

    /** The session's cookies as they are now (the site may have renewed them). */
    String cookie() {
        return cookie;
    }

    String serviceToken() {
        String token = cookieValue(cookie, "serviceToken");
        if (token != null && token.length() > 1 && token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1);
        }
        return token;
    }

    // ---- requests

    /** GET [path] with [params] (+ ts), the site's `data` object. */
    JSONObject get(String path, Map<String, String> params) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        if (params != null) query.putAll(params);
        query.put("ts", String.valueOf(System.currentTimeMillis()));
        return call("GET", base + path + "?" + encode(query), null);
    }

    /** POST [form] (+ serviceToken) to [path], the site's `data` object. */
    JSONObject post(String path, Map<String, String> form) throws IOException {
        Map<String, String> body = new LinkedHashMap<>(form);
        String token = serviceToken();
        if (token == null) throw new SessionExpired("no serviceToken");
        body.put("serviceToken", token);
        return call("POST", base + path, encode(body).getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject call(String method, String url, byte[] body) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpURLConnection c = open(url, true);
            try {
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod(method);
                c.setRequestProperty("Accept", "application/json, text/plain, */*");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                }
                int status = c.getResponseCode();
                remember(c);
                if (status == 401 || status == 403) throw new SessionExpired("HTTP " + status);
                if (status >= 300 && status < 400) {
                    // The site sends a signed-out caller to Xiaomi's sign-in page.
                    String location = c.getHeaderField("Location");
                    if (location != null && (location.contains("serviceLogin") || location.contains("account.xiaomi.com"))) {
                        throw new SessionExpired("redirected to sign-in");
                    }
                    throw new IOException("HTTP " + status + " for " + path(url));
                }
                if (status == 429 || status >= 500) {
                    last = new IOException("HTTP " + status);
                    pause(retryDelay(c, attempt));
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " for " + path(url));
                String text = new String(readAll(c.getInputStream(), 16L << 20), StandardCharsets.UTF_8);
                return unwrap(text, url);
            } catch (SessionExpired | ApiError e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt == MAX_RETRIES) break;
                pause(500L * attempt);
            } finally {
                c.disconnect();
            }
        }
        throw last != null ? last : new IOException("request failed");
    }

    private static JSONObject unwrap(String text, String url) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("not JSON from " + path(url));
        }
        if (!json.has("code")) return json;
        int code = json.optInt("code", -1);
        if (code != 0) throw new ApiError(code, "error " + code + " from " + path(url) + ": " + json.optString("description"));
        JSONObject data = json.optJSONObject("data");
        return data != null ? data : new JSONObject();
    }

    /**
     * Downloads a note attachment the way the page shows it (/file/full?type=note_img&fileid=..., which redirects to
     * Xiaomi's file CDN) into [dest]; the session cookie only goes to the site itself, never to the CDN. Returns the
     * Content-Type.
     */
    String download(String fileId, File dest) throws IOException {
        String url = base + "/file/full?" + encode(map("type", "note_img", "fileid", fileId));
        for (int hop = 0; hop < 6; hop++) {
            boolean ours = sameSite(url);
            HttpURLConnection c = open(url, ours);
            try {
                c.setInstanceFollowRedirects(false);
                int status = c.getResponseCode();
                if (ours) remember(c);
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("redirect without Location");
                    url = new URL(new URL(url), location).toString();
                    if (!allowHttp && !url.startsWith("https://")) throw new IOException("refusing non-https redirect");
                    continue;
                }
                if (ours && (status == 401 || status == 403)) throw new SessionExpired("HTTP " + status);
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " for attachment");
                File tmp = new File(dest.getPath() + ".part");
                File dir = dest.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 << 10];
                    long total = 0;
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        total += n;
                        if (total > MAX_DOWNLOAD) throw new IOException("attachment too large");
                        out.write(buffer, 0, n);
                    }
                    if (total == 0) throw new IOException("empty attachment");
                }
                if (!tmp.renameTo(dest)) throw new IOException("cannot save " + dest.getName());
                String type = c.getContentType();
                return type == null ? "" : type.toLowerCase(Locale.ROOT);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private boolean sameSite(String url) {
        Uri u = Uri.parse(url), b = Uri.parse(base);
        return u.getHost() != null && u.getHost().equalsIgnoreCase(b.getHost()) && u.getPort() == b.getPort();
    }

    private HttpURLConnection open(String url, boolean withSession) throws IOException {
        if (!allowHttp && !url.startsWith("https://")) throw new IOException("refusing non-https URL");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setUseCaches(false);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (withSession) {
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Referer", base + "/note/h5");
        }
        return c;
    }

    /** The site may renew its cookies on any answer: use them from now on, and keep them if the user asked us to. */
    private void remember(HttpURLConnection c) {
        Map<String, List<String>> headers = c.getHeaderFields();
        if (headers == null) return;
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            if (h.getKey() == null || !h.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String header : h.getValue()) {
                String pair = header.split(";", 2)[0].trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq);
                if (!name.equals("serviceToken") && !name.equals("userId")) continue;
                cookie = replaceCookie(cookie, name, pair.substring(eq + 1));
                if (keepCookies) {
                    try {
                        CookieManager.getInstance().setCookie(base, header);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static String replaceCookie(String cookies, String name, String value) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        if (cookies != null) {
            for (String part : cookies.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (sb.length() > 0) sb.append("; ");
                if (p.startsWith(name + "=")) {
                    sb.append(name).append('=').append(value);
                    found = true;
                } else {
                    sb.append(p);
                }
            }
        }
        if (!found) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(name).append('=').append(value);
        }
        return sb.toString();
    }

    private static long retryDelay(HttpURLConnection c, int attempt) {
        String after = c.getHeaderField("Retry-After");
        if (after != null) {
            try {
                return Math.min(60_000L, Math.max(0L, Long.parseLong(after.trim()) * 1000L));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.min(5_000L, 500L * attempt);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] readAll(InputStream in, long max) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 << 10];
            long total = 0;
            for (int n; (n = input.read(buffer)) > 0; ) {
                total += n;
                if (total > max) throw new IOException("response too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null) continue;
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8").replace("+", "%20"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    private static String path(String url) {
        return Uri.parse(url).getPath();
    }

    static void log(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message);
    }
}
