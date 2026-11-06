package org.ungoogled.samsungnotes.account;

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

/**
 * Samsung account's own endpoints: the sign-in page Notes itself opens when the Samsung Account app is missing, and the
 * OAuth token endpoint that renews its access token with the refresh token from that page. Only Notes' client ID and
 * the tokens Samsung issued go out; nothing about the phone is changed or invented.
 */
final class SamsungAuth {
    static final String TAG = "USamsungAccount";
    static final String ACCOUNT_SITE = "https://account.samsung.com";
    static final String DEFAULT_AUTH_SERVER = "https://auth2.samsungosp.com";

    enum Result { OK, REFUSED, NETWORK }

    private SamsungAuth() {
    }

    /**
     * Notes' web sign-in page (SamsungAccountLoginActivity.getLoginUri in the app): Samsung's account check for the app's
     * service ID, which signs in and hands back Notes' tokens.
     */
    static String signInUrl(String clientId, java.util.Locale locale) {
        return ACCOUNT_SITE + "/mobile/account/check.do?accessToken=Y&serviceID=" + Uri.encode(clientId)
            + "&actionID=StartOAuth2&countryCode=" + Uri.encode(locale.getCountry())
            + "&languageCode=" + Uri.encode(locale.getLanguage());
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
            return Long.decode(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Renews the access token with the refresh token (grant_type=refresh_token for Notes' client ID). */
    static Result refresh(AccountStore store, String clientId) {
        String refreshToken = store.refreshToken();
        if (AccountStore.empty(refreshToken) || store.refreshExpired()) return Result.REFUSED;
        String server = store.authServer();
        if (AccountStore.empty(server)) server = DEFAULT_AUTH_SERVER;
        if (!server.startsWith("http")) server = "https://" + server;
        String body = "grant_type=refresh_token&client_id=" + Uri.encode(clientId) + "&refresh_token=" + Uri.encode(refreshToken);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(server + "/auth/oauth2/token").openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("x-osp-appId", clientId);
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
                if (AccountStore.empty(t.accessToken)) {
                    Log.w(TAG, "token answer without an access token");
                    return Result.REFUSED;
                }
                if (!AccountStore.empty(t.userId) && !t.userId.equals(store.userId())) {
                    Log.w(TAG, "token answer for another account");
                    return Result.REFUSED;
                }
                store.save(t);
                Log.i(TAG, "access token renewed");
                return Result.OK;
            }
            Log.w(TAG, "token refresh refused: HTTP " + code + " " + errorOf(text));
            return code >= 500 ? Result.NETWORK : Result.REFUSED;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "token refresh failed: " + e);
            return Result.NETWORK;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String errorOf(String text) {
        try {
            JSONObject json = new JSONObject(text);
            return json.optString("error_code", "") + " " + json.optString("error", "");
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
