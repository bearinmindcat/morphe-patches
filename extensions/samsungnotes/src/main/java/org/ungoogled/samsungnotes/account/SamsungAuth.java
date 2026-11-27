package org.ungoogled.samsungnotes.account;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Samsung account's own endpoints: the sign-in page Notes itself opens when the Samsung Account app is missing, and the
 * OAuth token endpoint that renews its access token with the refresh token from that page. Only Notes' client ID and
 * the tokens Samsung issued go out; nothing about the phone is changed or invented.
 *
 * <p>What happens is logged under the tag "UA" (`adb logcat -s UA`), never a token itself.
 */
final class SamsungAuth {
    /** `setprop log.tag.USamsungAccount DEBUG` turns on the test hooks. */
    static final String TAG = "USamsungAccount";
    private static final String LOG = "UA";
    static final String ACCOUNT_SITE = "https://account.samsung.com";
    /** Where Samsung's own web-account client (Samsung Internet without the account app) renews its tokens. */
    static final String DEFAULT_AUTH_SERVER = "https://us-auth2.samsungosp.com";
    /** A token Notes calls expired this soon after it was issued is a repeated request, not an expired token. */
    private static final long JUST_RENEWED_MS = 30_000L;
    /** Trying Samsung's web sign-in quietly again (its own session) at most this often after it did not work. */
    private static final long QUIET_RETRY_MS = 6 * 3_600_000L;
    private static final long QUIET_SOON_MS = 10 * 60_000L;
    private static final Object RENEWAL = new Object();

    enum Result { OK, REFUSED, NETWORK }

    private SamsungAuth() {
    }

    static void log(String message) {
        Log.i(LOG, "SamsungAccount: " + message);
    }

    static void warn(String message) {
        Log.w(LOG, "SamsungAccount: " + message);
    }

    /**
     * Notes' web sign-in page (SamsungAccountLoginActivity.getLoginUri in the app): Samsung's account check for the app's
     * service ID, which signs in and hands back Notes' tokens.
     */
    static String signInUrl(String clientId, Locale locale) {
        return ACCOUNT_SITE + "/mobile/account/check.do?accessToken=Y&serviceID=" + Uri.encode(clientId)
            + "&actionID=StartOAuth2&countryCode=" + Uri.encode(locale.getCountry())
            + "&languageCode=" + Uri.encode(locale.getLanguage());
    }

    static boolean samsungHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("samsung.com") || h.endsWith(".samsung.com") || h.endsWith(".samsungosp.com") || h.endsWith(".samsungaccount.com");
    }

    /** A page address that may carry tokens: Samsung's own https pages only. */
    static boolean trusted(Uri uri) {
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (scheme.equals("http")) return false;
        return !scheme.equals("https") || samsungHost(uri.getHost());
    }

    /**
     * The tokens in a page address Samsung's sign-in ends on ("signInSuccessOAuth2", parameters in the query or the
     * fragment). Null if the address carries no access token.
     */
    static AccountStore.Token parse(Uri uri) {
        if (uri == null) return null;
        String access = param(uri, "access_token");
        if (AccountStore.empty(access)) return null;
        AccountStore.Token t = new AccountStore.Token();
        t.accessToken = access;
        t.refreshToken = param(uri, "refresh_token");
        t.accessSeconds = number(first(param(uri, "access_token_expires_in"), param(uri, "expires_in")));
        t.refreshSeconds = number(param(uri, "refresh_token_expires_in"));
        t.userId = first(param(uri, "userId"), param(uri, "user_id"));
        t.loginId = first(param(uri, "inputEmailID"), param(uri, "login_id"));
        t.apiServer = param(uri, "api_server_url");
        t.authServer = param(uri, "auth_server_url");
        return t;
    }

    /** One log line about a token answer: which parts Samsung sent, never their values. */
    static String describe(AccountStore.Token t) {
        return "access token " + (AccountStore.empty(t.accessToken) ? "no" : "yes")
            + (t.accessSeconds > 0 ? " (" + t.accessSeconds + " s)" : " (no lifetime)")
            + ", refresh token " + (AccountStore.empty(t.refreshToken) ? "NO" : "yes")
            + (t.refreshSeconds > 0 ? " (" + t.refreshSeconds + " s)" : "")
            + ", user " + (AccountStore.empty(t.userId) ? "no" : "yes")
            + ", auth server " + (AccountStore.empty(t.authServer) ? "none" : t.authServer);
    }

    static String param(Uri uri, String name) {
        try {
            String v = uri.getQueryParameter(name);
            if (!AccountStore.empty(v)) return v;
        } catch (UnsupportedOperationException ignored) {
            // Not a hierarchical address.
        }
        String fragment = uri.getEncodedFragment();
        if (fragment == null) return null;
        for (String pair : fragment.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(Uri.decode(pair.substring(0, eq)))) return Uri.decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String first(String a, String b) {
        return AccountStore.empty(a) ? b : a;
    }

    private static long number(String s) {
        if (AccountStore.empty(s)) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A current access token in the store for Notes. It is renewed when it ran out or when Notes names it as the expired
     * one, first with the refresh token, then (refused or none) through Samsung's sign-in page with the web session
     * this sign-in left. One renewal at a time: each refresh token is good for one use, and a second request with the
     * old one would be refused. REFUSED only when Samsung no longer accepts the refresh token or has none.
     */
    static Result current(Context context, AccountStore store, String clientId, String expired) {
        synchronized (RENEWAL) {
            String token = store.accessToken();
            boolean named = !AccountStore.empty(expired) && expired.equals(token);
            boolean ranOut = store.accessExpired();
            if (!named && !ranOut) {
                if (!AccountStore.empty(expired)) log("Notes named an older token as expired; the current one is newer");
                return Result.OK;
            }
            if (named && !ranOut && store.accessAge() < JUST_RENEWED_MS) {
                log("Notes asked again right after a renewal; keeping the new token");
                return Result.OK;
            }
            log("renewing the access token (" + (named ? "Notes says it expired" : "its lifetime ended") + ", issued "
                + store.accessAge() / 60_000L + " min ago)");
            Result r = refresh(store, clientId);
            if (r == Result.REFUSED && quietSignIn(context, store, clientId)) r = Result.OK;
            if (r == Result.NETWORK && !named && !AccountStore.empty(token)) {
                // Not renewed for now; the old token may still be accepted, and Notes asks again when it is not.
                log("keeping the old token until Samsung can be reached");
                return Result.OK;
            }
            return r;
        }
    }

    /** Renews the access token with the refresh token (grant_type=refresh_token for Notes' client ID). */
    private static Result refresh(AccountStore store, String clientId) {
        String refreshToken = store.refreshToken();
        if (AccountStore.empty(refreshToken)) {
            warn("no refresh token from Samsung's sign-in page; cannot renew silently");
            store.status("No refresh token from the sign-in page");
            return Result.REFUSED;
        }
        if (store.refreshExpired()) log("the refresh token is past its stated lifetime; asking Samsung anyway");
        String server = normalise(store.authServer());
        Result r = refreshAt(store, clientId, refreshToken, server);
        if (r == Result.NETWORK && !server.equals(DEFAULT_AUTH_SERVER)) {
            log("trying Samsung's main token server");
            r = refreshAt(store, clientId, refreshToken, DEFAULT_AUTH_SERVER);
        }
        return r;
    }

    private static String normalise(String server) {
        if (AccountStore.empty(server)) return DEFAULT_AUTH_SERVER;
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("http://")) s = "https://" + s.substring(7);
        if (!s.startsWith("https://")) s = "https://" + s;
        return s;
    }

    /** The same request Samsung's own web-account client sends: form body, client ID, no secret. */
    private static Result refreshAt(AccountStore store, String clientId, String refreshToken, String server) {
        String body = "grant_type=refresh_token&client_id=" + Uri.encode(clientId) + "&refresh_token=" + Uri.encode(refreshToken);
        HttpURLConnection c = null;
        String host = Uri.parse(server).getHost();
        try {
            c = (HttpURLConnection) new URL(server + "/auth/oauth2/token").openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Accept", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code == 200) {
                JSONObject json = new JSONObject(text);
                AccountStore.Token t = new AccountStore.Token();
                t.accessToken = json.optString("access_token", null);
                t.refreshToken = json.optString("refresh_token", null);
                t.accessSeconds = number(json.has("access_token_expires_in") ? json.optString("access_token_expires_in") : json.optString("expires_in"));
                t.refreshSeconds = number(json.optString("refresh_token_expires_in"));
                t.userId = json.optString("userId", null);
                t.apiServer = json.optString("api_server_url", null);
                t.authServer = json.optString("auth_server_url", null);
                if (AccountStore.empty(t.accessToken)) {
                    warn("token server " + host + " answered without an access token");
                    store.status("Renewal answer had no access token");
                    return Result.NETWORK;
                }
                if (!AccountStore.empty(t.userId) && !t.userId.equals(store.userId())) {
                    // A refresh token belongs to the account it was issued for; the stored ID stays.
                    warn("token server named a different user ID form; keeping the stored one");
                }
                store.renewed(t);
                log("access token renewed at " + host + ": " + describe(t));
                return Result.OK;
            }
            String error = errorOf(text);
            warn("token server " + host + " answered HTTP " + code + " " + error);
            if (code == 400 || code == 401) {
                store.status("Samsung refused the renewal: HTTP " + code + " " + error);
                return Result.REFUSED;
            }
            // Rate limits, maintenance, gateways (Samsung's own client treats 403 and 412 like this too).
            store.status("Renewal failed: HTTP " + code + " " + error);
            return Result.NETWORK;
        } catch (IOException | JSONException e) {
            warn("token request to " + host + " failed: " + e);
            store.status("Renewal failed: " + e.getClass().getSimpleName());
            return Result.NETWORK;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Samsung's sign-in page for Notes again, unseen, with the web session the user's own sign-in left in this app: if
     * Samsung still knows the session it hands out new tokens without asking. Same page, same account, no password.
     */
    private static boolean quietSignIn(Context context, AccountStore store, String clientId) {
        if (context == null) return false;
        long last = store.quietFailedAt();
        if (last != 0 && System.currentTimeMillis() - last < QUIET_RETRY_MS) {
            log("not retrying the quiet sign-in yet (last try " + (System.currentTimeMillis() - last) / 60_000L + " min ago)");
            return false;
        }
        log("trying Samsung's sign-in page quietly with this app's Samsung web session");
        QuietSignIn.Outcome o = QuietSignIn.run(context.getApplicationContext(), clientId, store.debugQuietUrl());
        AccountStore.Token t = o.token;
        if (t == null) {
            warn("quiet sign-in did not finish (" + o.where + "); the user has to sign in again");
            // No page at all (no WebView, or the app was paused in the background): worth another try soon.
            long now = System.currentTimeMillis();
            store.quietFailed(o.loaded ? now : now - QUIET_RETRY_MS + QUIET_SOON_MS);
            return false;
        }
        if (AccountStore.empty(t.userId) || !t.userId.equals(store.userId())) {
            warn("quiet sign-in returned " + (AccountStore.empty(t.userId) ? "no account" : "another account") + "; not used");
            store.quietFailed(System.currentTimeMillis());
            return false;
        }
        store.signedInAgain(t);
        log("signed in again quietly: " + describe(t));
        return true;
    }

    private static String errorOf(String text) {
        try {
            JSONObject json = new JSONObject(text);
            return (json.optString("error_code", "") + " " + json.optString("error", "") + " "
                + json.optString("error_description", "")).trim();
        } catch (JSONException e) {
            return "";
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = s.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
