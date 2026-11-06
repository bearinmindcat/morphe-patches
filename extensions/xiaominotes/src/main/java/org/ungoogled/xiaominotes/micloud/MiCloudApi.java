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
import java.util.regex.Pattern;

/**
 * The calls Xiaomi's own notes website (i.mi.com/note) makes, made the same way with the session the user signed in to
 * in our WebView: the page's cookies, the WebView's user agent, `ts` on every GET and the session's serviceToken in
 * every POST form. Nothing else is sent: no device id, no account data beyond the session cookies.
 */
final class MiCloudApi {
    static final String TAG = "UMiCloud";
    /** Tag of the one-line traces a reporter's log needs (host, step, status; never cookies or note text). */
    static final String TRACE = "UA";

    /** Xiaomi Cloud's notes site: i.mi.com and every regional host (us., de., sg., ru., in., ...). */
    static final Pattern NOTES_HOST = Pattern.compile("(?i)^(?:[a-z0-9-]+\\.)*i\\.mi\\.com$");
    /** Debug-only stand-ins for that family (a local copy of the site on the emulator's host). */
    private static final Pattern DEBUG_HOST = Pattern.compile("^(?:10\\.0\\.2\\.2|127\\.0\\.0\\.1|localhost)$");

    /** A failure the screen can explain: which host, which step, what the site answered. */
    static class Failure extends IOException {
        final String host, step;

        Failure(String host, String step, String message) {
            super(message);
            this.host = host;
            this.step = step;
        }
    }

    /** The session is gone (HTTP 401/403 or a redirect to sign-in): sign in again. */
    static final class SessionExpired extends Failure {
        SessionExpired(String host, String step, String why) {
            super(host, step, "Xiaomi Cloud ended the session (" + why + " at " + step + ", " + host + "). Sign in again.");
        }
    }

    /** The site answered with an error code. */
    static final class ApiError extends Failure {
        final int code;

        ApiError(String host, String step, int code, String detail) {
            super(host, step, "Xiaomi Cloud answered " + (detail == null || detail.isEmpty() ? String.valueOf(code) : detail)
                + " at " + step + " (" + host + ").");
            this.code = code;
        }
    }

    /** The site answered with an HTTP error or something that is not its data. */
    static final class HttpError extends Failure {
        final int status;

        HttpError(String host, String step, int status, String what) {
            super(host, step, "Xiaomi Cloud answered " + what + " at " + step + " (" + host + ").");
            this.status = status;
        }
    }

    /**
     * The account lives on another regional host: the site answers code 308 with data.redirectUrl (its own scripts then
     * reload the page there), or redirects to it.
     */
    static final class RegionMoved extends Failure {
        final String target;

        RegionMoved(String host, String step, String target) {
            super(host, step, "Xiaomi Cloud moved this account from " + host + " to " + Uri.parse(target).getHost() + ".");
            this.target = target;
        }
    }

    /** The host could not be reached at all. */
    static final class Unreachable extends Failure {
        Unreachable(String host, String step, IOException cause) {
            super(host, step, "Couldn't reach " + host + " (" + step + "): " + describe(cause));
            initCause(cause);
        }
    }

    private static String describe(IOException e) {
        if (e instanceof java.net.UnknownHostException) return "no such host / no internet";
        if (e instanceof java.net.SocketTimeoutException) return "timed out";
        if (e instanceof javax.net.ssl.SSLException) return "secure connection failed";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static final int MAX_RETRIES = 3;
    private static final long MAX_DOWNLOAD = 64L << 20;

    final String base;
    final String host;
    private final String userAgent;
    private final boolean allowHttp;
    private final boolean keepCookies;
    private String cookie;

    MiCloudApi(String base, String cookie, String userAgent, boolean allowHttp, boolean keepCookies) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        Uri b = Uri.parse(this.base);
        this.host = b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "");
        this.cookie = cookie;
        this.userAgent = userAgent;
        this.allowHttp = allowHttp;
        this.keepCookies = keepCookies;
        if (!allowHttp && !this.base.startsWith("https://")) throw new IllegalArgumentException("not https: " + base);
        if (!family(b, allowHttp)) throw new IllegalArgumentException("not a Xiaomi Cloud host: " + b.getHost());
    }

    /** True for i.mi.com and its regional hosts over https (and, in debug runs only, the local test hosts). */
    static boolean family(Uri uri, boolean debug) {
        if (uri == null || uri.getHost() == null) return false;
        if (debug && DEBUG_HOST.matcher(uri.getHost()).matches()) return true;
        return "https".equals(uri.getScheme()) && NOTES_HOST.matcher(uri.getHost()).matches();
    }

    /** "https://host[:port]" of a URL. */
    static String origin(Uri u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /**
     * The session cookies the WebView holds for [base], as the page itself would send them (cookie paths differ, so
     * the pages the site uses are asked too). Null when there is none.
     */
    static String sessionCookies(String base) {
        CookieManager cm = CookieManager.getInstance();
        Map<String, String> all = new LinkedHashMap<>();
        for (String path : new String[] {"/", "/note/h5", "/note/", "/note/v2/", "/sts"}) {
            String c = cm.getCookie(base + path);
            if (c == null) continue;
            for (String part : c.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && !all.containsKey(p.substring(0, eq))) all.put(p.substring(0, eq), p.substring(eq + 1));
            }
        }
        if (all.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
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

    static boolean hasSession(String cookies) {
        String token = cookieValue(cookies, "serviceToken");
        return token != null && !token.isEmpty() && !token.equals("\"\"");
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
        return call("GET", path, base + path + "?" + encode(query), null);
    }

    /** POST [form] (+ serviceToken) to [path], the site's `data` object. */
    JSONObject post(String path, Map<String, String> form) throws IOException {
        Map<String, String> body = new LinkedHashMap<>(form);
        String token = serviceToken();
        if (token == null) throw new SessionExpired(host, step("POST", path), "no serviceToken cookie");
        body.put("serviceToken", token);
        return call("POST", path, base + path, encode(body).getBytes(StandardCharsets.UTF_8));
    }

    /** "GET /note/note/<id>/" -- the call without ids, for messages and traces. */
    static String step(String method, String path) {
        return method + " " + path.replaceAll("/\\d{3,}", "/<id>");
    }

    private JSONObject call(String method, String path, String url, byte[] body) throws IOException {
        String step = step(method, path);
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
                if (status == 401 || status == 403) {
                    trace(step + " @" + host + " -> HTTP " + status);
                    throw new SessionExpired(host, step, "HTTP " + status);
                }
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    Uri to = location == null ? null : Uri.parse(new URL(new URL(url), location).toString());
                    if (to != null && to.getHost() != null && !allowHttp && family(Uri.parse("https://" + to.getHost()), false)) {
                        to = Uri.parse("https://" + to.getHost() + (to.getPath() == null ? "" : to.getPath()));
                    }
                    trace(step + " @" + host + " -> HTTP " + status + " to " + (to == null ? "nowhere" : to.getHost() + to.getPath()));
                    if (to != null && family(to, allowHttp) && !origin(to).equalsIgnoreCase(base)) {
                        throw new RegionMoved(host, step, origin(to));
                    }
                    if (to != null && (to.getPath() != null && to.getPath().contains("serviceLogin")
                        || to.getHost() != null && to.getHost().endsWith("account.xiaomi.com"))) {
                        throw new SessionExpired(host, step, "sent to sign-in");
                    }
                    throw new HttpError(host, step, status, "HTTP " + status);
                }
                if (status == 429 || status >= 500) {
                    trace(step + " @" + host + " -> HTTP " + status + " (try " + attempt + ")");
                    last = new HttpError(host, step, status, "HTTP " + status);
                    pause(retryDelay(c, attempt));
                    continue;
                }
                if (status < 200 || status >= 300) {
                    trace(step + " @" + host + " -> HTTP " + status);
                    throw new HttpError(host, step, status, "HTTP " + status);
                }
                String text = new String(readAll(c.getInputStream(), 16L << 20), StandardCharsets.UTF_8);
                return unwrap(text, step);
            } catch (Failure e) {
                throw e;
            } catch (IOException e) {
                trace(step + " @" + host + " -> " + e.getClass().getSimpleName() + " (try " + attempt + ")");
                last = new Unreachable(host, step, e);
                if (attempt == MAX_RETRIES) break;
                pause(500L * attempt);
            } finally {
                c.disconnect();
            }
        }
        throw last != null ? last : new HttpError(host, step, 0, "nothing");
    }

    private JSONObject unwrap(String text, String step) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (JSONException e) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            boolean page = t.startsWith("<") || t.contains("<html");
            trace(step + " @" + host + " -> HTTP 200 " + (page ? "web page" : "not JSON") + " (" + text.length() + " bytes)");
            throw new HttpError(host, step, 200, page ? "with a web page instead of data" : "with something that isn't data");
        }
        if (!json.has("code")) {
            trace(step + " @" + host + " -> HTTP 200 (no code)");
            return json;
        }
        Object rawCode = json.opt("code");
        int code = json.optInt("code", Integer.MIN_VALUE);
        trace(step + " @" + host + " -> HTTP 200 code " + rawCode);
        if (code == 0) {
            JSONObject data = json.optJSONObject("data");
            return data != null ? data : new JSONObject();
        }
        if (code == 308) {
            // The website's request helper: code 308 = this account lives on data.redirectUrl's host.
            JSONObject data = json.optJSONObject("data");
            String redirect = data != null ? data.optString("redirectUrl", "") : "";
            if (redirect.isEmpty()) redirect = json.optString("redirectUrl", "");
            Uri to = redirect.isEmpty() ? null : Uri.parse(redirect.contains("://") ? redirect : "https://" + redirect);
            // The site always reloads on https://<that host>; only test runs keep a plain-HTTP address.
            if (to != null && to.getHost() != null && !allowHttp) to = Uri.parse("https://" + to.getHost());
            if (to != null && family(to, allowHttp) && !origin(to).equalsIgnoreCase(base)) {
                trace(step + " @" + host + " -> region " + to.getHost());
                throw new RegionMoved(host, step, origin(to));
            }
            throw new ApiError(host, step, 308, "308 (a region switch to " + (to == null ? "no host" : to.getHost()) + ")");
        }
        String description = json.optString("description", json.optString("reason", json.optString("result", "")));
        if (description.length() > 80) description = description.substring(0, 80);
        throw new ApiError(host, step, code == Integer.MIN_VALUE ? -1 : code, rawCode + (description.isEmpty() ? "" : " (" + description + ")"));
    }

    static void trace(String message) {
        Log.i(TRACE, "micloud " + message);
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
                if (ours && (status == 401 || status == 403)) {
                    trace("GET /file/full @" + host + " -> HTTP " + status);
                    throw new SessionExpired(host, "GET /file/full", "HTTP " + status);
                }
                if (status < 200 || status >= 300) {
                    trace("GET /file/full @" + Uri.parse(url).getHost() + " -> HTTP " + status);
                    throw new IOException("HTTP " + status + " for attachment");
                }
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

    static void log(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message);
    }
}
