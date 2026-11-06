package org.ungoogled.samsungnotes.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.Locale;

/**
 * The Samsung account signed in on Samsung's web page: the tokens Samsung's sign-in page returned for Notes' own client
 * ID, kept in Notes' private storage (Notes is excluded from Android backups). Nothing here is sent anywhere but to
 * Samsung, by Notes' own sync engine.
 */
final class AccountStore {
    private static final String PREFS = "ungoogled_samsung_account";
    private static AccountStore instance;

    private final SharedPreferences prefs;

    private AccountStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized AccountStore get(Context context) {
        if (instance == null) instance = new AccountStore(context);
        return instance;
    }

    boolean signedIn() {
        return !empty(prefs.getString("user_id", null)) && !empty(prefs.getString("access_token", null));
    }

    String loginName() {
        String name = prefs.getString("login_id", null);
        return empty(name) ? "Samsung account" : name;
    }

    String userId() {
        return prefs.getString("user_id", null);
    }

    String accessToken() {
        return prefs.getString("access_token", null);
    }

    String refreshToken() {
        return prefs.getString("refresh_token", null);
    }

    String authServer() {
        return prefs.getString("auth_server_url", null);
    }

    /** The access token's end, with five minutes to spare; unknown lifetimes count as one day (Samsung's usual). */
    boolean accessExpired() {
        long until = prefs.getLong("access_until", 0);
        return until != 0 && System.currentTimeMillis() > until - 5 * 60_000L;
    }

    boolean refreshExpired() {
        long until = prefs.getLong("refresh_until", 0);
        return until != 0 && System.currentTimeMillis() > until;
    }

    boolean needsSignIn() {
        return prefs.getBoolean("needs_sign_in", false);
    }

    void needsSignIn(boolean on) {
        prefs.edit().putBoolean("needs_sign_in", on).apply();
    }

    /** Stores a sign-in or refresh result; fields the answer leaves out keep their old value. */
    void save(Token t) {
        SharedPreferences.Editor e = prefs.edit();
        long now = System.currentTimeMillis();
        if (!empty(t.accessToken)) {
            e.putString("access_token", t.accessToken);
            e.putLong("access_issued", now);
            e.putLong("access_until", now + (t.accessSeconds > 0 ? t.accessSeconds : 86_400L) * 1000L);
        }
        if (!empty(t.refreshToken)) {
            e.putString("refresh_token", t.refreshToken);
            e.putLong("refresh_until", t.refreshSeconds > 0 ? now + t.refreshSeconds * 1000L : 0);
        }
        if (!empty(t.userId)) e.putString("user_id", t.userId);
        if (!empty(t.loginId)) e.putString("login_id", t.loginId);
        if (!empty(t.apiServer)) e.putString("api_server_url", t.apiServer);
        if (!empty(t.authServer)) e.putString("auth_server_url", t.authServer);
        e.putBoolean("needs_sign_in", false);
        e.commit();
    }

    void clear() {
        prefs.edit().clear().commit();
    }

    /** What the Samsung Account app hands Notes for an access token request (keys of its "additional" list). */
    Bundle toResult() {
        Bundle b = new Bundle();
        b.putString("access_token", accessToken());
        b.putString("user_id", userId());
        b.putString("login_id", prefs.getString("login_id", null));
        String api = prefs.getString("api_server_url", null);
        if (!empty(api)) b.putString("api_server_url", api);
        String auth = authServer();
        if (!empty(auth)) {
            b.putString("auth_server_url", auth);
            b.putString("server_url", auth);
        }
        // The account's country is not in the web answer; the phone's region stands in for it (Notes uses it for
        // its update check and China-only behaviour).
        try {
            b.putString("cc", Locale.getDefault().getISO3Country());
        } catch (java.util.MissingResourceException e) {
            b.putString("cc", Locale.getDefault().getCountry());
        }
        b.putBoolean("is_child_account", false);
        long issued = prefs.getLong("access_issued", 0);
        long until = prefs.getLong("access_until", 0);
        if (issued != 0) b.putLong("token_issue_time", issued);
        if (until != 0 && issued != 0) b.putLong("token_expires_in", (until - issued) / 1000L);
        return b;
    }

    static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /** A token answer from Samsung (sign-in page or token endpoint). */
    static final class Token {
        String accessToken;
        String refreshToken;
        long accessSeconds;
        long refreshSeconds;
        String userId;
        String loginId;
        String apiServer;
        String authServer;
    }
}
