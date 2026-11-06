package org.ungoogled.gmail;

import android.net.Uri;

import java.util.Locale;

/** Strip link trackers: the link a message opens, without Google's redirect and without tracking parameters. */
public final class Links {
    private Links() {}

    /** Click-tracking parameters added by mail and ad platforms; a conservative list, nothing a site needs to work. */
    private static final String[] TRACKERS = {
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "igshid", "ttclid",
            "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok",
    };

    /**
     * In place of the URL Gmail's message view is about to open: a www.google.com/url?q= redirect becomes the address
     * it wraps, and utm_* and the parameters above are dropped. Anything that is not an http(s) or gmail-link link, or
     * that cannot be parsed, opens unchanged.
     */
    public static String clean(String url) {
        if (url == null) return null;
        try {
            if (url.startsWith(GMAIL_LINK)) return cleanGmailLink(url);
            return cleanWeb(url);
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * Gmail's message view hands every tapped link over as gmail-link://?url=<link>&safeRedirectUrl=<redirect>&msgId=..
     * and opens the redirect -- www.google.com/url, which records the click -- in place of the link when there is one.
     * The link is cleaned and the redirect dropped, except when it comes with a reason: then it is a suspicious-link
     * warning, which stays.
     */
    private static String cleanGmailLink(String url) {
        int at = url.indexOf('?');
        if (at < 0) return url;
        String[] pairs = url.substring(at + 1).split("&");
        boolean warning = false;
        for (String pair : pairs) if (pair.startsWith("safeRedirectReason=")) warning = true;
        StringBuilder out = new StringBuilder(url.substring(0, at + 1));
        boolean first = true;
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            if (!warning && pair.startsWith("safeRedirectUrl=")) continue;
            if (pair.startsWith("url=")) pair = "url=" + Uri.encode(cleanWeb(Uri.decode(pair.substring(4))));
            if (!first) out.append('&');
            out.append(pair);
            first = false;
        }
        return out.toString();
    }

    private static final String GMAIL_LINK = "gmail-link:";

    /** A web link without Google's redirect and without tracking parameters. */
    private static String cleanWeb(String url) {
        if (url == null) return null;
        try {
            String out = url;
            for (int i = 0; i < 3; i++) {
                String inner = unwrap(out);
                if (inner == null) break;
                out = inner;
            }
            return strip(out);
        } catch (Throwable t) {
            return url;
        }
    }

    /** The address a google.com/url redirect wraps, or null when [url] is not one. */
    private static String unwrap(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        if (!host.equals("google.com") && !host.equals("www.google.com")) return null;
        if (!"/url".equals(uri.getPath())) return null;
        String target = uri.getQueryParameter("q");
        if (target == null || target.isEmpty()) target = uri.getQueryParameter("url");
        if (target == null) return null;
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? target : null;
    }

    /** [url] without tracking parameters; the rest of the query keeps its order and encoding. */
    private static String strip(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null) return url;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return url;
        String query = uri.getEncodedQuery();
        if (query == null || query.isEmpty()) return url;
        StringBuilder kept = new StringBuilder();
        boolean dropped = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = Uri.decode(eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (tracker(name)) {
                dropped = true;
                continue;
            }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        if (!dropped) return url;
        return uri.buildUpon().encodedQuery(kept.length() == 0 ? null : kept.toString()).build().toString();
    }

    private static boolean tracker(String name) {
        if (name.startsWith("utm_")) return true;
        for (String t : TRACKERS) if (t.equals(name)) return true;
        return false;
    }
}
