package org.ungoogled.samsungnotes.account;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Samsung's sign-in page for Notes in an unseen WebView: with a Samsung web session still alive in this app (the one the
 * user's own sign-in left), Samsung's page goes straight to its token address. Anything that needs the user (password,
 * verification, terms) does not finish here and is left to the visible sign-in.
 */
final class QuietSignIn {
    private static final long TIMEOUT_MS = 40_000L;

    static final class Outcome {
        volatile AccountStore.Token token;
        /** Where the page stopped (host and path, no parameters), for the log. */
        volatile String where = "no page loaded";
        /** Whether any page loaded at all. */
        volatile boolean loaded;
    }

    private QuietSignIn() {
    }

    /** Blocks the calling thread (never the main thread) until the page hands out tokens or gives up. */
    static Outcome run(Context app, String clientId, String debugUrl) {
        Outcome outcome = new Outcome();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            outcome.where = "called on the main thread";
            return outcome;
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<WebView> view = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                WebView web = new WebView(app);
                view.set(web);
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                web.setWebViewClient(new WebViewClient() {
                    private boolean take(String url) {
                        if (url == null || finished.getCount() == 0) return false;
                        Uri uri = Uri.parse(url);
                        outcome.where = uri.getHost() + uri.getPath();
                        outcome.loaded = true;
                        if (!SamsungAuth.trusted(uri)) return false;
                        AccountStore.Token t = SamsungAuth.parse(uri);
                        if (t != null) {
                            outcome.token = t;
                            finished.countDown();
                            return true;
                        }
                        if ("true".equals(SamsungAuth.param(uri, "close"))) {
                            outcome.where += " closed: " + SamsungAuth.param(uri, "error_code");
                            finished.countDown();
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        if (take(request.getUrl().toString())) return true;
                        String scheme = request.getUrl().getScheme();
                        return scheme == null || !scheme.equals("https");
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        if (take(url)) v.stopLoading();
                    }

                    @Override
                    public void onPageFinished(WebView v, String url) {
                        take(url);
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                        outcome.where = "page renderer stopped";
                        finished.countDown();
                        return true;
                    }
                });
                boolean debug = !AccountStore.empty(debugUrl) && android.util.Log.isLoggable(SamsungAuth.TAG, android.util.Log.DEBUG);
                web.loadUrl(debug ? debugUrl : SamsungAuth.signInUrl(clientId, Locale.getDefault()));
            } catch (Throwable e) {
                outcome.where = "no WebView: " + e;
                finished.countDown();
            }
        });
        try {
            if (!finished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) outcome.where += " (no answer in " + TIMEOUT_MS / 1000 + " s)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            WebView web = view.getAndSet(null);
            if (web == null) return;
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
                // Already gone.
            }
        });
        return outcome;
    }
}
