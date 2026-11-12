package org.ungoogled.ui;

import android.content.Context;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Serial native requests, checkpointed in small batches rather than once per result. */
final class PlaceEnrichment {
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "UA-enrichment"));
    private static boolean running;
    private static final int MAX_ATTEMPTS = 5;

    private PlaceEnrichment() {}

    static synchronized void start(Context context) {
        if (!Shapes.localSavedPatched() || running) return;
        running = true;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> drain(app));
    }

    static void pause(Context c, boolean paused) {
        synchronized (SavedStore.class) {
            SavedStore.enrichmentPaused = paused;
            SavedStore.save(c);
        }
        if (!paused) start(c);
    }

    static void cancel(Context c) {
        synchronized (SavedStore.class) {
            SavedStore.enrichmentPaused = true;
            for (SavedStore.Place p : SavedStore.places.values()) {
                if (waiting(p)) {
                    if (metadataWaiting(p)) p.detailsState = "cancelled";
                    if (!"ready".equals(p.thumbnailState)) p.thumbnailState = "cancelled";
                }
            }
            SavedStore.save(c);
        }
    }

    static void retry(Context c, SavedStore.Place only) {
        synchronized (SavedStore.class) {
            for (SavedStore.Place p : SavedStore.places.values()) {
                if (only != null && !p.id.equals(only.id)) continue;
                if (only == null && ("review".equals(p.detailsState) ||
                        "complete".equals(p.detailsState) &&
                        ("ready".equals(p.thumbnailState) || "unavailable".equals(p.thumbnailState)))) continue;
                p.detailsState = only != null || p.metadataAt == 0 ? "pending" : "complete";
                p.thumbnailState = p.photos.isEmpty() ? "none" : "pending";
                p.detailError = "";
                p.thumbnailError = "";
                p.attempts = 0;
                p.thumbnailAttempts = 0;
                p.retryAt = 0;
            }
            SavedStore.enrichmentPaused = false;
            SavedStore.save(c);
        }
        start(c);
    }

    static String status(SavedStore.Place p) {
        if (!Shapes.localSavedPatched() && metadataWaiting(p)) return "Background enrichment is not included in this profile";
        switch (p.detailsState) {
            case "pending": return "Details queued";
            case "loading": return "Loading details";
            case "retry": return "Request failed; retry scheduled";
            case "failed": return "Details failed; tap Retry";
            case "review": return "Maps returned a different listing; review its identity";
            case "unavailable": return p.detailError.isEmpty() ? "Place details unavailable" : p.detailError;
            case "cancelled": return "Enrichment cancelled; entry retained";
            case "complete":
                if ("ready".equals(p.thumbnailState)) return "Details and thumbnail downloaded";
                if ("unavailable".equals(p.thumbnailState)) return "Details loaded; no representative photo available";
                if ("failed".equals(p.thumbnailState)) return "Details loaded; thumbnail failed (Retry)";
                if ("cancelled".equals(p.thumbnailState)) return "Details loaded; thumbnail download cancelled";
                return "Details loaded; thumbnail queued";
            default: return p.located() ? "" : "No verified coordinates yet";
        }
    }

    static String progress() {
        int total = 0, ready = 0, photos = 0, pending = 0, failed = 0;
        synchronized (SavedStore.class) {
            for (SavedStore.Place p : SavedStore.places.values()) {
                if ("none".equals(p.detailsState)) continue;
                total++;
                if ("complete".equals(p.detailsState)) ready++;
                if ("ready".equals(p.thumbnailState)) photos++;
                if (waiting(p)) pending++;
                if ("failed".equals(p.detailsState) || "unavailable".equals(p.detailsState) || "review".equals(p.detailsState) ||
                        "failed".equals(p.thumbnailState)) failed++;
            }
            return (SavedStore.enrichmentPaused ? "Paused. " : "") + ready + "/" + total + " details, "
                    + photos + " thumbnails downloaded; " + pending + " queued, " + failed + " need attention.";
        }
    }

    private static boolean waiting(SavedStore.Place p) {
        return metadataWaiting(p)
                || "pending".equals(p.thumbnailState)
                || "failed".equals(p.thumbnailState) && p.thumbnailAttempts < MAX_ATTEMPTS;
    }

    private static boolean metadataWaiting(SavedStore.Place p) {
        return "pending".equals(p.detailsState) || "loading".equals(p.detailsState) || "retry".equals(p.detailsState);
    }

    private static void drain(Context c) {
        long delay = -1;
        try {
            SavedStore.load(c);
            List<SavedStore.Place> batch = new ArrayList<>();
            synchronized (SavedStore.class) {
                if (SavedStore.enrichmentPaused) return;
                long now = System.currentTimeMillis();
                for (SavedStore.Place p : SavedStore.places.values()) {
                    if ("ready".equals(p.thumbnailState) && !Thumbs.cached(c, p.photo())) {
                        p.thumbnailState = "pending"; p.thumbnailAttempts = 0; p.retryAt = 0;
                    }
                    if (!waiting(p)) continue;
                    if (p.retryAt > now) {
                        delay = delay < 0 ? p.retryAt - now : Math.min(delay, p.retryAt - now);
                        continue;
                    }
                    if (metadataWaiting(p) && !NativePlaces.available()) {
                        delay = 10000;
                        continue;
                    }
                    if (batch.size() == 5) { delay = 500; break; }
                    if (metadataWaiting(p)) p.detailsState = "loading";
                    batch.add(p);
                }
                if (!batch.isEmpty()) SavedStore.save(c);
            }
            if (batch.isEmpty()) return;
            SavedStore.flush();
            for (SavedStore.Place p : batch) {
                synchronized (SavedStore.class) {
                    if (SavedStore.enrichmentPaused) {
                        if ("loading".equals(p.detailsState)) p.detailsState = "pending";
                        continue;
                    }
                    if (SavedStore.find(p.id) == null) continue;
                }
                enrich(c, p);
            }
            synchronized (SavedStore.class) {
                SavedStore.save(c);
                for (SavedStore.Place p : SavedStore.places.values()) if (waiting(p)) {
                    long next = Math.max(1000, p.retryAt - System.currentTimeMillis());
                    delay = delay < 0 ? next : Math.min(delay, next);
                }
            }
            SavedStore.flush();
        } catch (Exception | LinkageError e) {
            android.util.Log.e("UA", "Import enrichment stopped; entries and checkpoints retained", e);
            synchronized (SavedStore.class) { SavedStore.enrichmentPaused = true; }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(c, "Enrichment paused after an error. Entries are retained; use Retry.", android.widget.Toast.LENGTH_LONG).show());
        } finally {
            synchronized (PlaceEnrichment.class) { running = false; }
            synchronized (SavedStore.class) {
                if (!SavedStore.enrichmentPaused && delay >= 0) WORKER.schedule(() -> start(c), delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void enrich(Context c, SavedStore.Place target) {
        boolean thumbnailPhase = !metadataWaiting(target);
        try {
            if (!thumbnailPhase) {
                SavedStore.Place identity;
                synchronized (SavedStore.class) { identity = target.displayCopy(); }
                if (identity.ftid == null && identity.cid == null && identity.placeId == null && SavedStore.isShortMapsLink(identity.sourceUrl)) {
                    synchronized (SavedStore.class) { target.attempts++; }
                    identity = resolveShortLink(identity);
                }
                String feature = identity.ftid;
                if (feature == null && identity.cid != null && !"0".equals(identity.cid)) {
                    try { feature = "0x0:0x" + Long.toHexString(Long.parseUnsignedLong(identity.cid)); }
                    catch (NumberFormatException e) { throw new Unavailable("Invalid CID; original entry retained"); }
                }
                if (feature == null && identity.placeId != null) {
                    feature = NativePlaces.featureFromPlaceId(identity.placeId);
                    if (feature == null) throw new NativePlaces.UnsupportedPlaceId();
                }
                if (feature == null) throw new Unavailable("No verified Maps identifier; original entry retained");
                synchronized (SavedStore.class) { target.attempts++; }
                SavedStore.Place details = NativePlaces.lookup(c, feature);
                synchronized (SavedStore.class) {
                    if (!"loading".equals(target.detailsState) || SavedStore.find(target.id) == null) return;
                    String requestedCid = identity.cid != null ? identity.cid : SavedPlaces.cid(feature);
                    if (requestedCid == null || !requestedCid.equals(SavedPlaces.cid(details.ftid))) {
                        target.candidateFtid = details.ftid == null ? "" : details.ftid;
                        target.candidateName = details.name;
                        throw new IdentityReview("Maps returned a different listing. Its old-ID relationship could not be verified; original identity retained.");
                    }
                    SavedStore.applyDetails(target, details);
                }
            }
            thumbnailPhase = true;
            if (target.photo().isEmpty()) {
                synchronized (SavedStore.class) { target.thumbnailState = "unavailable"; }
            } else {
                synchronized (SavedStore.class) { target.thumbnailAttempts++; }
                String thumbnail = prefetchRepresentative(c, target);
                synchronized (SavedStore.class) {
                    if (!"cancelled".equals(target.thumbnailState)) {
                        target.thumbnailUrl = thumbnail;
                        target.thumbnailState = "ready";
                    }
                }
            }
            synchronized (SavedStore.class) { target.retryAt = 0; target.thumbnailError = ""; }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            synchronized (SavedStore.class) {
                if ("cancelled".equals(target.detailsState) || "cancelled".equals(target.thumbnailState) ||
                        !thumbnailPhase && !"loading".equals(target.detailsState)) return;
                if (root instanceof NativePlaces.ServiceUnavailable) {
                    target.detailsState = "pending";
                    target.attempts = Math.max(0, target.attempts - 1);
                    target.retryAt = System.currentTimeMillis() + 10000;
                    return;
                }
                if (thumbnailPhase) target.thumbnailState = "failed";
                else if (root instanceof IdentityReview) target.detailsState = "review";
                else if (root instanceof Unavailable || root instanceof NativePlaces.UnsupportedPlaceId || message.contains("NOT_FOUND"))
                    target.detailsState = "unavailable";
                else target.detailsState = target.attempts < MAX_ATTEMPTS ? "retry" : "failed";
                target.retryAt = System.currentTimeMillis() + Math.min(TimeUnit.HOURS.toMillis(1),
                        30000L << Math.min(7, Math.max(0, (thumbnailPhase ? target.thumbnailAttempts : target.attempts) - 1)));
                String safeMessage = message.length() > 200 ? message.substring(0, 200) : message;
                if (thumbnailPhase) target.thumbnailError = safeMessage;
                else target.detailError = safeMessage;
                if (thumbnailPhase && "complete".equals(target.detailsState) &&
                        target.metadataAt > 0 && System.currentTimeMillis() - target.metadataAt > TimeUnit.DAYS.toMillis(1) &&
                        target.attempts < MAX_ATTEMPTS) target.detailsState = "pending";
            }
            android.util.Log.w("UA", "Import entry remains unresolved: " + root.getClass().getSimpleName());
        }
    }

    private static String prefetchRepresentative(Context c, SavedStore.Place place) throws Exception {
        List<String> candidates = new ArrayList<>();
        synchronized (SavedStore.class) {
            if (!place.thumbnailUrl.isEmpty()) candidates.add(place.thumbnailUrl);
            for (String photo : place.photos) {
                if (!candidates.contains(photo)) candidates.add(photo);
                if (candidates.size() == 6) break;
            }
        }
        Exception failure = new IOException("No representative thumbnail URL is available");
        for (String url : candidates) {
            try { Thumbs.prefetch(c, url); return url; }
            catch (Exception e) {
                failure = e;
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (cause instanceof java.net.UnknownHostException || cause instanceof java.net.ConnectException ||
                        cause instanceof java.net.SocketTimeoutException || String.valueOf(cause.getMessage()).contains("HTTP 429")) break;
            }
        }
        throw failure;
    }

    private static SavedStore.Place resolveShortLink(SavedStore.Place place) throws IOException {
        URL current = new URL(place.sourceUrl);
        for (int i = 0; i < 5; i++) {
            String host = current.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!"https".equals(current.getProtocol()) || current.getUserInfo() != null ||
                    current.getPort() != -1 && current.getPort() != 443 ||
                    !(host.equals("maps.app.goo.gl") || host.equals("goo.gl") ||
                            host.matches("(?:www\\.|maps\\.)?google\\.(?:com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})")))
                throw new Unavailable("Maps link redirects outside supported Google hosts");
            SavedStore.Place parsed = SavedStore.fromMapsLink(current.toString(), place.name);
            if (parsed.ftid != null || parsed.cid != null || parsed.placeId != null) return parsed;
            HttpURLConnection connection = LibraryNetwork.open(current);
            try {
                int status = connection.getResponseCode();
                if (status == 404 || status == 410) throw new Unavailable("Source link is unavailable; entry retained");
                if (status < 300 || status >= 400) throw new Unavailable("Source link has no verified place identifier");
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Maps redirect has no destination");
                current = new URL(current, location);
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many Maps link redirects");
    }

    private static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
    }

    private static final class IdentityReview extends IOException {
        IdentityReview(String message) { super(message); }
    }
}
