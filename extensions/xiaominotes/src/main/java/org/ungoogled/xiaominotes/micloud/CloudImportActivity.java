package org.ungoogled.xiaominotes.micloud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Import from Xiaomi Cloud" (Settings > Xiaomi Cloud). A consent line, then Xiaomi's own notes website in a WebView:
 * the user signs in on Xiaomi's page (password, 2FA and captcha stay between the user and Xiaomi; we never see them).
 * Once the page has a session, the notes are read with that session's cookies (see MiCloudApi) and written into Notes
 * (CloudImporter). The session is forgotten afterwards unless the user ticks "Stay signed in".
 */
public final class CloudImportActivity extends Activity {
    static final String START_URL = "https://i.mi.com/note/h5";
    /** Xiaomi Cloud's notes site: i.mi.com and its regional hosts (us.i.mi.com, ...). */
    private static final Pattern NOTES_HOST = Pattern.compile("(?i)^([a-z0-9-]+\\.)?i\\.mi\\.com$");

    /** The screen on show, which gets an import's progress and result even if it was reopened meanwhile. */
    private static java.lang.ref.WeakReference<CloudImportActivity> current = new java.lang.ref.WeakReference<>(null);

    private interface Screen {
        void show(CloudImportActivity a);
    }

    private void ui(Screen screen) {
        main.post(() -> {
            CloudImportActivity a = current.get();
            if (a != null && !a.finished) screen.show(a);
        });
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> origins = new LinkedHashSet<>();
    private State state;
    private FrameLayout root;
    private WebView web;
    private boolean night;
    private boolean probing;
    private String probedCookie;
    private boolean finished;
    /** "Sync now" was tapped: after signing in, sync instead of importing. */
    private boolean syncRequested;
    private String debugBase;

    // ---- debug override (adb only)

    /**
     * Tests point the importer at a local copy of the site: `adb shell setprop log.tag.UMiCloud DEBUG`, then start this
     * screen with `--es debug_base http://10.0.2.2:PORT` (it is not exported, so only adb as root can). Without that
     * property the extra and the stored value are ignored.
     */
    private String debugBase() {
        if (!Log.isLoggable(MiCloudApi.TAG, Log.DEBUG)) return null;
        String extra = getIntent() != null ? getIntent().getStringExtra("debug_base") : null;
        if (extra != null) state.set("debugBase", extra.isEmpty() ? null : extra);
        return state.get("debugBase");
    }

    // ---- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_DeviceDefault_NoActionBar : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        state = State.get(this);
        debugBase = debugBase();
        root = new FrameLayout(this);
        root.setBackgroundColor(background());
        getWindow().setStatusBarColor(background());
        getWindow().setNavigationBarColor(background());
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int types = WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout();
            android.graphics.Insets i = insets.getInsets(types);
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        if (!night && getWindow().getInsetsController() != null) {
            // Dark status and navigation bar icons on the white page (the decor exists once the content is set).
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        }
        current = new java.lang.ref.WeakReference<>(this);
        if (CloudImporter.active() != null) showWork(CloudImporter.active() instanceof CloudSync ? "Syncing…" : "Importing notes…", 0, 0);
        else showIntro();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        if (current.get() == this) current = new java.lang.ref.WeakReference<>(null);
        destroyWeb();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.getParent() != null) {
            if (web.canGoBack()) web.goBack();
            else {
                endSession();
                showIntro();
            }
            return;
        }
        CloudImporter job = CloudImporter.active();
        if (job != null) {
            job.cancelled = true;
            return;
        }
        super.onBackPressed();
    }

    // ---- screens

    /** Notes' own look: black or white page, its yellow accent for the main button. */
    private static final int ACCENT = 0xFFFDC038;

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
        title.setText("Import from Xiaomi Cloud");
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

    private Button button(LinearLayout column, String label, View.OnClickListener click) {
        return button(column, label, false, click);
    }

    private Button button(LinearLayout column, String label, boolean primary, View.OnClickListener click) {
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
        return b;
    }

    private void showIntro() {
        LinearLayout column = page();
        text(column, "Notes will sign in to Xiaomi's cloud website (i.mi.com) with your Xiaomi account and download your notes.", 17, false);
        text(column, "You sign in on Xiaomi's own page; Notes does not see or keep your password. Encrypted and hidden notes can't be imported.", 14, true);
        String last = state.get("lastImport");
        if (last != null) {
            text(column, "Last import: " + DateFormat.getDateFormat(this).format(Long.parseLong(last)) + " "
                + DateFormat.getTimeFormat(this).format(Long.parseLong(last)) + ". Importing again adds new notes and updates changed ones.", 14, true);
        }
        CheckBox stay = new CheckBox(this);
        stay.setText("Stay signed in for the next import");
        stay.setTextColor(foreground());
        stay.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        stay.setChecked(state.flag("staySignedIn"));
        stay.setOnCheckedChangeListener((v, on) -> {
            state.flag("staySignedIn", on);
            if (!on) forgetSession();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        column.addView(stay, lp);
        if (debugBase != null) text(column, "Debug: using " + debugBase, 12, true);
        button(column, "Continue", true, v -> {
            syncRequested = false;
            start();
        });
        button(column, "Cancel", v -> finish());
        syncSection(column);
    }

    /** "Sync with Xiaomi Cloud (experimental)": off by default; while on, Notes syncs when it opens and on "Sync now". */
    private void syncSection(LinearLayout column) {
        TextView header = text(column, "Two-way sync", 14, true);
        header.setPadding(0, dp(32), 0, dp(4));
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setText("Sync with Xiaomi Cloud (experimental)");
        toggle.setTextColor(foreground());
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggle.setChecked(state.flag("syncEnabled"));
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(state.flag("syncEnabled") ? ACCENT : 0xFFBDBDBD));
        column.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text(column, "Untested with real accounts: edits, new notes and deletions on this phone change your notes in Xiaomi Cloud.", 13, true);
        toggle.setOnCheckedChangeListener((v, on) -> {
            state.flag("syncEnabled", on);
            if (!on && !state.flag("staySignedIn")) forgetSession();
            showIntro();
        });
        if (!state.flag("syncEnabled")) return;
        String status;
        String last = state.get("lastSync");
        if (state.flag("needsSignIn")) status = "Sign in again to keep syncing.";
        else if (last == null) status = "Not synced yet.";
        else status = "Last sync: " + DateFormat.getDateFormat(this).format(Long.parseLong(last)) + " "
            + DateFormat.getTimeFormat(this).format(Long.parseLong(last)) + ".";
        if (state.flag("e2ee")) status += " This account uses end-to-end encryption, so sync only downloads.";
        text(column, status, 14, false);
        button(column, "Sync now", v -> {
            syncRequested = true;
            start();
        });
    }

    private void showWork(String what, int done, int total) {
        if (finished) return;
        LinearLayout column;
        TextView status = root.findViewWithTag("status");
        ProgressBar bar = root.findViewWithTag("bar");
        if (status == null || bar == null) {
            column = page();
            status = text(column, what, 16, false);
            status.setTag("status");
            bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setTag("bar");
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            button(column, "Cancel", v -> {
                CloudImporter job = CloudImporter.active();
                if (job != null) job.cancelled = true;
            });
        }
        status.setText(total > 0 ? what + " " + done + " of " + total : what);
        bar.setIndeterminate(total <= 0);
        if (total > 0) {
            bar.setMax(total);
            bar.setProgress(done);
        }
    }

    private void showDone(String summary) {
        LinearLayout column = page();
        text(column, summary, 17, false);
        button(column, "Done", true, v -> finish());
    }

    private void showError(String message) {
        LinearLayout column = page();
        text(column, message, 17, false);
        button(column, "Try again", true, v -> start());
        button(column, "Close", v -> finish());
    }

    // ---- sign-in

    private String startUrl() {
        return debugBase != null ? debugBase + "/note/h5" : START_URL;
    }

    private boolean notesSite(Uri uri) {
        if (uri == null || uri.getHost() == null) return false;
        if (debugBase != null) {
            Uri d = Uri.parse(debugBase);
            if (uri.getHost().equalsIgnoreCase(d.getHost()) && uri.getPort() == d.getPort()) return true;
        }
        return "https".equals(uri.getScheme()) && NOTES_HOST.matcher(uri.getHost()).matches();
    }

    /** With "Stay signed in", the kept session is tried first; otherwise (or if it has expired) Xiaomi's sign-in page. */
    private void start() {
        String cookie = keepSession() ? state.get("session") : null;
        String base = state.get("sessionBase");
        if (cookie == null || base == null || !notesSite(Uri.parse(base + "/note"))) {
            showWeb();
            return;
        }
        showWork("Connecting…", 0, 0);
        MiCloudApi api = new MiCloudApi(base, cookie, WebSettings.getDefaultUserAgent(this), debugBase != null, true);
        new Thread(() -> {
            boolean ok;
            try {
                api.get("/note/v2/hasData", null);
                ok = true;
            } catch (MiCloudApi.SessionExpired e) {
                ok = false;
            } catch (IOException e) {
                ok = true;
            }
            boolean signedIn = ok;
            main.post(() -> {
                if (finished) return;
                if (signedIn) startImport(api, MiCloudApi.cookieValue(cookie, "userId"));
                else {
                    state.set("session", null);
                    showWeb();
                }
            });
        }, "micloud-session").start();
    }

    private void showWeb() {
        root.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView hint = new TextView(this);
        hint.setText("Sign in with your Xiaomi account");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTextColor(foreground());
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        column.addView(hint);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // Stay in the page; app links (mimarket://, intent://) are not followed.
                return !"https".equals(scheme) && !("http".equals(scheme) && debugBase != null);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Uri u = Uri.parse(url);
                if (u.getScheme() != null && u.getHost() != null) origins.add(u.getScheme() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : ""));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                MiCloudApi.log("page " + Uri.parse(url).getHost() + Uri.parse(url).getPath());
                checkSignedIn(url);
            }
        });
        column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        probing = false;
        probedCookie = null;
        web.loadUrl(startUrl());
    }

    /** On Xiaomi's notes site with a session cookie: check the session works, then import from that host. */
    private void checkSignedIn(String url) {
        Uri u = Uri.parse(url);
        if (probing || CloudImporter.active() != null || !notesSite(u) || u.getPath() == null || !u.getPath().startsWith("/note")) return;
        String base = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String cookie = CookieManager.getInstance().getCookie(base);
        if (MiCloudApi.cookieValue(cookie, "serviceToken") == null || MiCloudApi.cookieValue(cookie, "userId") == null) return;
        if (cookie.equals(probedCookie)) return;
        probing = true;
        probedCookie = cookie;
        String userAgent = WebSettings.getDefaultUserAgent(this);
        boolean keep = keepSession();
        MiCloudApi api = new MiCloudApi(base, cookie, userAgent, debugBase != null, keep);
        new Thread(() -> {
            boolean ok;
            try {
                api.get("/note/v2/hasData", null);
                ok = true;
            } catch (MiCloudApi.SessionExpired e) {
                ok = false; // the page signs in again by itself
            } catch (IOException e) {
                ok = true; // the session is there; the importer reports real errors
            }
            boolean signedIn = ok;
            main.post(() -> {
                probing = false;
                if (signedIn && !finished) {
                    CookieManager.getInstance().flush();
                    startImport(api, MiCloudApi.cookieValue(cookie, "userId"));
                }
            });
        }, "micloud-probe").start();
    }

    private void startImport(MiCloudApi api, String userId) {
        if (CloudImporter.active() != null) return;
        boolean sync = syncRequested;
        destroyWeb();
        showWork("Connecting…", 0, 0);
        Context app = getApplicationContext();
        new Thread(() -> {
            String summary = null, error = null;
            CloudImporter job = null;
            try (NotesStore store = new NotesStore(app)) {
                accountCheck(userId);
                CloudImporter.Listener progress = (what, done, total) -> ui(a -> a.showWork(what, done, total));
                job = sync ? new CloudSync(app, api, state, store, progress) : new CloudImporter(app, api, state, store, progress);
                if (!CloudImporter.claim(job)) {
                    job = null;
                    throw new IOException("Notes is already syncing with Xiaomi Cloud; try again in a moment.");
                }
                summary = sync ? summaryOf((CloudSync) job) : job.run().summary();
            } catch (MiCloudApi.SessionExpired e) {
                if (sync) state.set("needsSignIn", "1");
                error = "Xiaomi Cloud ended the session. Sign in again to continue.";
            } catch (Throwable e) {
                Log.w(MiCloudApi.TAG, sync ? "sync failed" : "import failed", e);
                error = job != null && job.cancelled ? "Stopped. Notes copied so far are kept; running it again continues."
                    : (sync ? "Couldn't sync: " : "Couldn't import: ") + e.getMessage();
                if (sync) state.set("lastSyncError", String.valueOf(e.getMessage()));
            } finally {
                if (job != null) CloudImporter.release(job);
                // Keep the session (as the site last renewed it) only if the user asked to stay signed in or syncs.
                if (keepSession()) {
                    state.set("session", api.cookie());
                    state.set("sessionBase", api.base);
                }
            }
            String s = summary, err = error;
            main.post(this::endSession);
            ui(a -> {
                if (s != null) a.showDone(s);
                else a.showError(err);
            });
        }, sync ? "micloud-sync" : "micloud-import").start();
    }

    private static String summaryOf(CloudSync job) throws IOException {
        job.sync();
        return job.syncSummary();
    }

    /** Another account than last time: its notes have other ids, so the record of the old one does not apply. */
    private void accountCheck(String userId) {
        String id = State.hash(userId, "");
        String previous = state.get("account");
        if (previous != null && !previous.equals(id)) {
            state.clearRecords();
        }
        state.set("account", id);
    }

    // ---- session

    /** Forgets the session unless the user asked to stay signed in. */
    private void endSession() {
        if (!keepSession()) forgetSession();
    }

    /** The session is kept when the user asked to stay signed in, and while sync is on (it needs it). */
    private boolean keepSession() {
        return state.flag("staySignedIn") || state.flag("syncEnabled");
    }

    /** Plain-HTTP test servers are allowed only with the adb-only debug property set. */
    static boolean debugAllowed(String base) {
        return Log.isLoggable(MiCloudApi.TAG, Log.DEBUG);
    }

    private void forgetSession() {
        state.set("session", null);
        state.set("sessionBase", null);
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        WebStorage storage = WebStorage.getInstance();
        for (String origin : origins) storage.deleteOrigin(origin);
        storage.deleteOrigin("https://i.mi.com");
        storage.deleteOrigin("https://account.xiaomi.com");
    }

    private void destroyWeb() {
        if (web == null) return;
        WebView w = web;
        web = null;
        ViewGroup parent = (ViewGroup) w.getParent();
        if (parent != null) parent.removeView(w);
        w.stopLoading();
        if (!keepSession()) {
            w.clearCache(true);
            w.clearFormData();
            w.clearHistory();
        }
        w.destroy();
    }

    static Intent intent(Context context) {
        return new Intent(context, CloudImportActivity.class);
    }
}
