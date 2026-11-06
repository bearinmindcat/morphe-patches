package org.ungoogled.samsungnotes.account;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Notes' Samsung account screens without the Samsung Account app: sign-in (Notes' "SIGNIN_POPUP"), its access token
 * request and the account check before "Sync now". Sign-in is Samsung's own page (account.samsung.com) for Notes'
 * client ID, after a consent line; the password, 2-step verification and captcha stay between the user and Samsung.
 * Opened from Settings it shows the account and Sign out.
 */
public final class SignInActivity extends Activity {
    private static final String TAG = SamsungAuth.TAG;
    static final String SIGNIN_POPUP = "com.msc.action.samsungaccount.SIGNIN_POPUP";
    static final String REQUEST_ACCESSTOKEN = "com.msc.action.samsungaccount.REQUEST_ACCESSTOKEN";
    static final String CHECKLIST = "com.msc.action.samsungaccount.REQUEST_CHECKLIST_VALIDATION";
    /** Notes' yellow accent. */
    private static final int ACCENT = 0xFFFDC038;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AccountStore store;
    private FrameLayout root;
    private WebView web;
    private boolean night;
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_DeviceDefault_NoActionBar : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        store = AccountStore.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(background());
        getWindow().setStatusBarColor(background());
        getWindow().setNavigationBarColor(background());
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout();
                android.graphics.Insets i = insets.getInsets(types);
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        if (!night && android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        }
        setResult(RESULT_CANCELED);
        if (debugResult()) return;
        String action = getIntent() != null ? getIntent().getAction() : null;
        if (action == null) {
            if (store.signedIn()) showAccount();
            else showIntro();
        } else if (store.signedIn() && !store.needsSignIn()) {
            // Notes asks for the account it already has: answer with a current token.
            showWork("Checking your Samsung account…");
            new Thread(this::answerSignedIn, "UASamsungAccountCheck").start();
        } else {
            showIntro();
        }
    }

    @Override
    protected void onDestroy() {
        done = true;
        destroyWeb();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.getParent() != null) {
            if (web.canGoBack()) web.goBack();
            else showIntro();
            return;
        }
        super.onBackPressed();
    }

    // ---- answers

    private void answerSignedIn() {
        SamsungAuth.Result r = SamsungAuth.Result.OK;
        if (store.accessExpired()) r = SamsungAuth.refresh(store, SamsungAccount.clientId());
        SamsungAuth.Result result = r;
        main.post(() -> {
            if (done) return;
            if (result == SamsungAuth.Result.OK) finishSignedIn();
            else if (result == SamsungAuth.Result.NETWORK) showMessage("Can't reach Samsung. Check your connection and try again.");
            else {
                store.needsSignIn(true);
                showIntro();
            }
        });
    }

    private void finishSignedIn() {
        Intent data = new Intent();
        data.putExtras(store.toResult());
        setResult(RESULT_OK, data);
        finish();
    }

    /** A token address from Samsung's sign-in page: keep it, tell Notes, and return. */
    private boolean accept(String url) {
        if (url == null || done) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (scheme.equals("https") && !samsungHost(uri.getHost())) return false;
        if (scheme.equals("http")) return false;
        AccountStore.Token t = SamsungAuth.parse(uri);
        if (t == null) {
            if ("true".equals(SamsungAuth.param(uri, "close"))) {
                String error = SamsungAuth.param(uri, "error_description");
                Log.w(TAG, "sign-in closed: " + SamsungAuth.param(uri, "error_code") + " " + error);
                main.post(() -> showMessage(error != null ? "Samsung: " + error : "Not signed in."));
                return true;
            }
            return false;
        }
        if (AccountStore.empty(t.userId)) {
            Log.w(TAG, "sign-in answer without an account ID");
            main.post(() -> showMessage("Samsung's page did not return your account. Try again."));
            return true;
        }
        if (store.signedIn() && store.userId() != null && !store.userId().equals(t.userId)) {
            // Another account: Notes' own sign-out first, so its sync state starts fresh.
            SamsungAccount.signOut(this);
        }
        store.save(t);
        Log.i(TAG, "signed in to a Samsung account");
        SamsungAccount.announce(this, true);
        main.post(this::finishSignedIn);
        return true;
    }

    private static boolean samsungHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("samsung.com") || h.endsWith(".samsung.com") || h.endsWith(".samsungosp.com") || h.endsWith(".samsungaccount.com");
    }

    /** Tests (adb only, with `setprop log.tag.USamsungAccount DEBUG`): --es debug_result <address the page ends on>. */
    private boolean debugResult() {
        String url = getIntent() != null ? getIntent().getStringExtra("debug_result") : null;
        if (url == null || !Log.isLoggable(TAG, Log.DEBUG)) return false;
        Log.d(TAG, "debug sign-in result");
        if (!accept(url)) showMessage("Debug: no token in that address.");
        return true;
    }

    // ---- screens

    private int background() {
        return night ? 0xFF000000 : 0xFFFFFFFF;
    }

    private int foreground() {
        return night ? 0xFFFFFFFF : 0xFF000000;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout page() {
        destroyWeb();
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(column);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(this);
        title.setText("Samsung account");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTextColor(foreground());
        title.setPadding(0, dp(16), 0, dp(16));
        column.addView(title);
        return column;
    }

    private TextView text(LinearLayout column, CharSequence s, float sp, boolean secondary) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(foreground());
        if (secondary) t.setAlpha(0.6f);
        t.setPadding(0, dp(6), 0, dp(6));
        column.addView(t);
        return t;
    }

    private void button(LinearLayout column, String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primary ? 0xFF000000 : foreground());
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(primary ? ACCENT : (night ? 0xFF262626 : 0xFFEFEFEF));
        b.setBackground(shape);
        b.setStateListAnimator(null);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(12);
        column.addView(b, lp);
    }

    /** The consent screen: nothing goes to Samsung before "Sign in". */
    private void showIntro() {
        LinearLayout column = page();
        if (store.needsSignIn()) text(column, "Your sign-in expired. Sign in again to keep syncing.", 17, false);
        text(column, "Sign in on Samsung's page (account.samsung.com) to sync your notes with Samsung Cloud.", 17, false);
        text(column, "Experimental. Your password goes to Samsung only. \"Continue with Google\" may not work here: use your Samsung account e-mail or phone and password.", 14, true);
        button(column, "Sign in", true, v -> startWeb());
        button(column, "Cancel", false, v -> finish());
    }

    private void showAccount() {
        LinearLayout column = page();
        text(column, store.loginName(), 20, false);
        text(column, store.needsSignIn() ? "Sign-in expired." : "Signed in on Samsung's web page (experimental).", 14, true);
        if (store.needsSignIn()) button(column, "Sign in again", true, v -> showIntro());
        button(column, "Sign out", false, v -> {
            SamsungAccount.signOut(this);
            showMessage("Signed out. Your notes stay on this phone.");
        });
    }

    private void showWork(String what) {
        LinearLayout column = page();
        text(column, what, 16, false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showMessage(String message) {
        if (done) return;
        LinearLayout column = page();
        text(column, message, 17, false);
        button(column, "Try again", true, v -> showIntro());
        button(column, "Close", false, v -> finish());
    }

    // ---- Samsung's page

    private void startWeb() {
        root.removeAllViews();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (accept(url)) return true;
                String scheme = request.getUrl().getScheme();
                // Only Samsung's web pages load here; other schemes (app links) are not followed.
                return scheme == null || !(scheme.equals("https") || scheme.equals("http"));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (accept(url)) view.stopLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                accept(url);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(SamsungAuth.signInUrl(SamsungAccount.clientId(), Locale.getDefault()));
    }

    private void destroyWeb() {
        if (web == null) return;
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        web.stopLoading();
        web.clearCache(true);
        web.destroy();
        web = null;
    }
}
