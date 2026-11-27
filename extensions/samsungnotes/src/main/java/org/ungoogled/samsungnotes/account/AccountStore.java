package org.ungoogled.samsungnotes.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Samsung account signed in on Samsung's web page: the tokens Samsung's sign-in page returned for Notes' own client
 * ID, kept in Notes' private storage (Notes is excluded from Android backups). Nothing here is sent anywhere but to
 * Samsung, by Notes' own sync engine. Only Sign out (or signing in with another account) clears it; a refused renewal
 * only marks it as needing a new sign-in.
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

    /** How long ago the current access token was issued (ms). */
    long accessAge() {
        long issued = prefs.getLong("access_issued", 0);
        return issued == 0 ? Long.MAX_VALUE / 2 : Math.max(0, System.currentTimeMillis() - issued);
    }

    /** Past the lifetime the sign-in stated; only informative, Samsung decides. */
    boolean refreshExpired() {
        long until = prefs.getLong("refresh_until", 0);
        return until != 0 && System.currentTimeMillis() > until;
    }

    boolean needsSignIn() {
        return prefs.getBoolean("needs_sign_in", false);
    }

    void needsSignIn(boolean on) {
        prefs.edit().putBoolean("needs_sign_in", on).commit();
    }

    long quietFailedAt() {
        return prefs.getLong("quiet_failed_at", 0);
    }

    void quietFailed(long when) {
        prefs.edit().putLong("quiet_failed_at", when).commit();
    }

    /** The last renewal problem, shown on the account screen. */
    void status(String what) {
        prefs.edit().putString("status", what).putLong("status_at", System.currentTimeMillis()).commit();
    }

    /** One line for the account screen: when the token was last renewed, and the last problem since. */
    String statusLine() {
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());
        StringBuilder s = new StringBuilder();
        long renewed = prefs.getLong("renewed_at", 0);
        long signedIn = prefs.getLong("signed_in_at", 0);
        if (signedIn != 0) s.append("Signed in ").append(f.format(new Date(signedIn))).append(". ");
        s.append(renewed != 0 ? "Token renewed " + f.format(new Date(renewed)) + "." : "Token not renewed yet.");
        if (empty(refreshToken())) s.append(" No refresh token.");
        long at = prefs.getLong("status_at", 0);
        String status = prefs.getString("status", null);
        if (!empty(status) && at > renewed) s.append("\n").append(status).append(" (").append(f.format(new Date(at))).append(")");
        return s.toString();
    }

    /** A sign-in on Samsung's page (a new session). */
    void save(Token t) {
        SharedPreferences.Editor e = prefs.edit();
        put(e, t);
        if (!empty(t.userId)) e.putString("user_id", t.userId);
        if (!empty(t.loginId)) e.putString("login_id", t.loginId);
        e.putLong("signed_in_at", System.currentTimeMillis());
        e.remove("renewed_at").remove("status").remove("status_at").remove("quiet_failed_at");
        e.putBoolean("needs_sign_in", false);
        e.commit();
    }

    /** A token endpoint answer: same account, new tokens (a refresh token is replaced when Samsung sends a new one). */
    void renewed(Token t) {
        SharedPreferences.Editor e = prefs.edit();
        put(e, t);
        e.putLong("renewed_at", System.currentTimeMillis());
        e.remove("quiet_failed_at");
        e.putBoolean("needs_sign_in", false);
        e.commit();
    }

    /** The same account signed in again through Samsung's page without the user (its web session). */
    void signedInAgain(Token t) {
        SharedPreferences.Editor e = prefs.edit();
        put(e, t);
        if (!empty(t.loginId)) e.putString("login_id", t.loginId);
        e.putLong("renewed_at", System.currentTimeMillis());
        e.remove("quiet_failed_at");
        e.putBoolean("needs_sign_in", false);
        e.commit();
    }

    /** Token fields; fields the answer leaves out keep their old value. */
    private static void put(SharedPreferences.Editor e, Token t) {
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
        if (!empty(t.apiServer)) e.putString("api_server_url", t.apiServer);
        if (!empty(t.authServer)) e.putString("auth_server_url", t.authServer);
    }

    /** Test hook (see SignInActivity.debugResult): where the quiet sign-in goes instead of Samsung's page. */
    String debugQuietUrl() {
        return prefs.getString("debug_quiet_url", null);
    }

    void debugQuietUrl(String url) {
        if (empty(url)) prefs.edit().remove("debug_quiet_url").commit();
        else prefs.edit().putString("debug_quiet_url", url).commit();
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
