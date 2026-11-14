package org.ungoogled.samsunginternet;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Samsung Internet starts its own fetches (update check, remote configuration, ad-block lists, Quick access sites and
 * icons, search engine icons, news, Safer browsing, Smart shopping, Firebase for its push) seconds after launch, while
 * its terms are still on screen. Until they are accepted, the app's own connections (every URL.openConnection and every
 * host lookup in its Java code, which OkHttp also uses) fail locally, as they do offline: nothing is resolved or sent.
 * Once the terms are accepted everything runs again when the app next asks, as it always has. Pages are loaded by the
 * Chromium engine, which is not touched.
 */
public final class TermsGate {
    private static final String TAG = "UngoogledTerms";

    private static volatile boolean accepted;
    private static volatile boolean heldAny;
    private static boolean refreshed;
    private static Context context;
    private static Method agreementNeeded;
    private static final Set<String> logged = new HashSet<>();
    private static final List<Object[]> deferredPush = new ArrayList<>();

    private TermsGate() {
    }

    /** Application.attachBaseContext. */
    public static void install(Context base) {
        context = base;
        try {
            agreementNeeded = Class.forName("com.sec.android.app.sbrowser.help_intro.utils.HelpIntroUtil", false,
                TermsGate.class.getClassLoader()).getMethod("isAgreementNeeded", Context.class);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "terms check not found, not holding anything", e);
            accepted = true;
        }
    }

    /**
     * HelpIntroHelper.handleActivityResult: the terms screen closed. Most held fetches run again by themselves as the
     * browser comes up; the ad blocker's default filter list is only fetched at start-up, so it is fetched now.
     */
    public static synchronized void termsClosed(int resultCode) {
        if (pending() || !heldAny || refreshed) return;
        refreshed = true;
        Log.i(TAG, "terms accepted, going online");
        // Samsung's push (SMP, which registers with Firebase through Play services) as it would have started.
        for (Object[] call : deferredPush) {
            try {
                for (Method m : call[0].getClass().getMethods()) {
                    if (m.getName().equals("initialize") && m.getParameterTypes().length == 1) {
                        m.invoke(call[0], call[1]);
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "push start failed", e);
            }
        }
        deferredPush.clear();
        try {
            Class<?> resources = Class.forName("com.sec.android.app.sbrowser.common.blockers.ContentBlockResourceManager");
            resources.getMethod("updateDefaultList", Context.class).invoke(resources.getDeclaredConstructor().newInstance(), context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "ad blocker list not refreshed", e);
        }
    }

    private static boolean pending() {
        if (accepted) return false;
        if (context == null) return true;
        try {
            if (!(Boolean) agreementNeeded.invoke(null, context)) {
                accepted = true;
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Unknown: keep holding rather than leak; the next request asks again.
            return true;
        }
    }

    /** SmpController.initialize(callback): true = held, started again after the terms (termsClosed). */
    public static synchronized boolean holdPush(Object controller, Object callback) {
        if (!pending()) return false;
        heldAny = true;
        Log.i(TAG, "push held until the terms are accepted");
        deferredPush.add(new Object[] {controller, callback});
        return true;
    }

    private static boolean held(String host) {
        if (!pending()) return false;
        heldAny = true;
        synchronized (logged) {
            if (logged.add(String.valueOf(host))) Log.i(TAG, "held until the terms are accepted: " + host);
        }
        return true;
    }

    public static URLConnection openConnection(URL url) throws IOException {
        if (held(url.getHost())) throw new IOException("offline until the terms are accepted");
        return url.openConnection();
    }

    public static URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        if (held(url.getHost())) throw new IOException("offline until the terms are accepted");
        return url.openConnection(proxy);
    }

    public static InputStream openStream(URL url) throws IOException {
        if (held(url.getHost())) throw new IOException("offline until the terms are accepted");
        return url.openStream();
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        if (held(host)) throw new UnknownHostException(host + ": offline until the terms are accepted");
        return InetAddress.getAllByName(host);
    }

    public static InetAddress getByName(String host) throws UnknownHostException {
        if (held(host)) throw new UnknownHostException(host + ": offline until the terms are accepted");
        return InetAddress.getByName(host);
    }
}
