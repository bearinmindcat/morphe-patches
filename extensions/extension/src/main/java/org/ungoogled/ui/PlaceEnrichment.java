package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Serial native requests, checkpointed in small batches rather than once per result. */
final class PlaceEnrichment {
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "UA-enrichment"));
    private static final ExecutorService CONTROLS = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "UA-import-controls"));
    private static final AtomicInteger CONTROL_PENDING = new AtomicInteger();
    private static final RunState RUN = new RunState();
    private static ScheduledFuture<?> scheduled;
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile String waitingReason = "";
    private static volatile String cachedProgress = "Preparing saved-place import.";
    private static volatile boolean serviceIdle, pauseRequested, doingWork;
    private static final AtomicLong NETWORK_CHANGES = new AtomicLong();
    private static final AtomicLong SERVICE_STOPS = new AtomicLong();
    private static final AtomicInteger IMPORTS = new AtomicInteger();
    private static final int MAX_ATTEMPTS = 5;

    private PlaceEnrichment() {}

    static void start(Context context) {
        if (!Shapes.localSavedPatched()) return;
        Context app = context.getApplicationContext();
        serviceIdle = false;
        synchronized (RUN) {
            if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
            if (RUN.request()) WORKER.execute(() -> drain(app));
        }
    }

    static final class RunState {
        private boolean running, signalled;

        synchronized boolean request() {
            if (running) { signalled = true; return false; }
            running = true;
            return true;
        }

        synchronized long finish(boolean paused, long delay) {
            running = false;
            boolean again = signalled;
            signalled = false;
            return again ? 0 : paused ? -1 : delay;
        }
    }

    static void foreground(Activity activity) {
        foreground = new WeakReference<>(activity);
        start(activity);
    }

    static void background(Activity activity) {
        if (foreground.get() == activity) foreground = new WeakReference<>(null);
    }

    static boolean isForeground() {
        Activity activity = foreground.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    static void beginImport(Activity activity) {
        if (!Shapes.localSavedPatched()) return;
        IMPORTS.incrementAndGet();
        serviceIdle = false;
        foreground = new WeakReference<>(activity);
        waitingReason = "Reading selected files; entries are saved before lookup.";
        cachedProgress = waitingReason;
        EnrichmentService.request(activity);
    }

    static void finishImport(Context c) {
        serviceIdle = false;
        IMPORTS.updateAndGet(count -> Math.max(0, count - 1));
        start(c);
    }

    static boolean isDoingWork() { return doingWork || IMPORTS.get() > 0; }

    static void networkChanged(Context c, boolean lost) {
        if (lost) NETWORK_CHANGES.incrementAndGet();
        start(c);
    }

    static boolean shouldStopService() {
        return IMPORTS.get() == 0 && CONTROL_PENDING.get() == 0 && serviceIdle;
    }

    private static boolean hasWork() {
        for (SavedStore.Place p : SavedStore.places.values()) if (waiting(p)) return true;
        return false;
    }

    static void serviceStopped() {
        SERVICE_STOPS.incrementAndGet();
        doingWork = false;
        waitingReason = "Background import stopped. Reopen Local saved to continue saved work.";
    }

    static void serviceUnavailable(Context c, String reason, RuntimeException error) {
        waitingReason = reason;
        cachedProgress = reason;
        android.util.Log.e("UA", "Visible import service could not start", error);
        WORKER.execute(() -> {
            try {
                SavedStore.load(c);
                synchronized (SavedStore.class) {
                    SavedStore.enrichmentLastFailure = reason;
                    SavedStore.save(c);
                }
                publishProgress();
            } catch (RuntimeException e) {
                android.util.Log.e("UA", "Import service failure could not be checkpointed; original store retained", e);
            }
        });
    }

    static void pause(Context c, boolean paused) { pause(c, paused, null); }

    static void pause(Context c, boolean paused, Runnable after) {
        controlAsync(c, paused ? "Paused by you. Tap Resume to continue." : "Resuming saved imports.", paused, () -> {
            SavedStore.enrichmentPaused = paused;
            SavedStore.enrichmentPauseReason = paused ? "Paused by you. Tap Resume to continue." : "";
            if (!paused) SavedStore.enrichmentLastFailure = "";
        }, after);
    }

    static void pauseAsync(Context c, String reason) {
        controlAsync(c, reason, true, () -> {
            SavedStore.enrichmentPaused = true;
            SavedStore.enrichmentPauseReason = reason;
        }, null);
    }

    static void cancelAsync(Context c) { cancel(c); }

    private static void controlAsync(Context c, String reason, boolean paused, Runnable change, Runnable after) {
        CONTROL_PENDING.incrementAndGet();
        pauseRequested = paused;
        serviceIdle = paused;
        cachedProgress = reason;
        Context app = c.getApplicationContext();
        if (!paused && Shapes.localSavedPatched()) EnrichmentService.request(c);
        CONTROLS.execute(() -> {
            try {
                SavedStore.load(app);
                synchronized (SavedStore.class) {
                    change.run();
                    SavedStore.save(app);
                }
                SavedStore.flush();
                publishProgress();
                if (!paused) start(app);
                if (after != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!(c instanceof Activity) || !((Activity) c).isFinishing() && !((Activity) c).isDestroyed()) after.run();
                });
            } catch (Exception e) {
                pauseRequested = true;
                android.util.Log.e("UA", "Import control could not be saved", e);
                cachedProgress = "Import control could not be saved. Check storage before reopening.";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(app, cachedProgress, android.widget.Toast.LENGTH_LONG).show());
            } finally {
                CONTROL_PENDING.decrementAndGet();
            }
        });
    }

    static void cancel(Context c) { cancel(c, null); }

    static void cancel(Context c, Runnable after) {
        controlAsync(c, "Queue cancelled. Imported entries are retained.", true, () -> {
            SavedStore.enrichmentPaused = true;
            SavedStore.enrichmentPauseReason = "Queue cancelled. Imported entries are retained.";
            for (SavedStore.Place p : SavedStore.places.values()) {
                if (waiting(p)) {
                    if (metadataWaiting(p)) p.detailsState = "cancelled";
                    if (!"ready".equals(p.thumbnailState)) p.thumbnailState = "cancelled";
                }
            }
        }, after);
    }

    static void retry(Context c, SavedStore.Place only) { retry(c, only, null); }

    static void retry(Context c, SavedStore.Place only, Runnable after) {
        controlAsync(c, "Retrying unfinished imports.", false, () -> {
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
            SavedStore.enrichmentPauseReason = "";
            SavedStore.enrichmentLastFailure = "";
        }, after);
    }

    static String status(SavedStore.Place p) {
        if (!Shapes.localSavedPatched() && metadataWaiting(p)) return "Background enrichment is not included in this profile";
        switch (p.detailsState) {
            case "pending": return waitingReason.isEmpty() ? "Details queued" : waitingReason;
            case "loading": return "Loading details";
            case "retry": return "Retry scheduled: " + p.detailError;
            case "failed": return "Details failed: " + p.detailError + " (Retry)";
            case "review": return "Maps returned a different listing; review its identity";
            case "unavailable": return p.detailError.isEmpty() ? "Place details unavailable" : p.detailError;
            case "cancelled": return "Enrichment cancelled; entry retained";
            case "complete":
                if ("ready".equals(p.thumbnailState)) return "Details and thumbnail downloaded";
                if ("unavailable".equals(p.thumbnailState)) return "Details loaded; no representative photo available";
                if ("failed".equals(p.thumbnailState)) return "Thumbnail failed: " + p.thumbnailError + " (Retry)";
                if ("cancelled".equals(p.thumbnailState)) return "Details loaded; thumbnail download cancelled";
                return "Details loaded; thumbnail queued";
            default: return p.located() ? "" : "No verified coordinates yet";
        }
    }

    static String progress() { return cachedProgress; }

    private static void publishProgress() {
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
            serviceIdle = IMPORTS.get() == 0 && (SavedStore.enrichmentPaused || pauseRequested || !hasWork());
            String reason = IMPORTS.get() > 0 ? "Reading selected files."
                    : SavedStore.enrichmentPaused ? SavedStore.enrichmentPauseReason
                    : pending > 0 ? waitingReason : "";
            if (SavedStore.enrichmentPaused && reason.isEmpty()) reason = "Paused. Tap Resume to continue.";
            cachedProgress = (reason.isEmpty() ? "" : reason + "\n") + ready + "/" + total + " details, "
                    + photos + " thumbnails downloaded; " + pending + " queued, " + failed + " need attention."
                    + (SavedStore.enrichmentLastFailure.isEmpty() ? "" : "\nLast failure: " + SavedStore.enrichmentLastFailure);
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
                if (SavedStore.enrichmentPaused || pauseRequested) return;
                if (IMPORTS.get() > 0) { delay = 1000; return; }
                for (SavedStore.Place p : SavedStore.places.values()) {
                    if ("ready".equals(p.thumbnailState) && !Thumbs.cached(c, p.photo())) {
                        p.thumbnailState = "pending"; p.thumbnailAttempts = 0; p.retryAt = 0;
                    }
                }
                if (!hasWork()) { waitingReason = ""; return; }
            }
            if (!EnrichmentService.running()) {
                waitingReason = isForeground() ? "Starting visible background import."
                        : "Waiting for you to reopen Local saved; Android has no active import service.";
                if (isForeground()) EnrichmentService.request(c);
                return;
            }
            synchronized (SavedStore.class) {
                long now = System.currentTimeMillis();
                for (SavedStore.Place p : SavedStore.places.values()) {
                    if (!waiting(p)) continue;
                    if (needsNetwork(c, p) && !networkAvailable(c)) {
                        waitingReason = networkWaitingReason(c);
                        delay = 30000;
                        continue;
                    }
                    if (p.retryAt > now) {
                        waitingReason = "Waiting for retry backoff; failed entries remain saved.";
                        delay = delay < 0 ? p.retryAt - now : Math.min(delay, p.retryAt - now);
                        continue;
                    }
                    if (metadataWaiting(p) && !NativePlaces.available()) {
                        waitingReason = "Waiting for Maps' place service. Return to the map and reopen Local saved; no retries used.";
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
            android.app.NotificationManager notifications = c.getSystemService(android.app.NotificationManager.class);
            waitingReason = !networkValidated(c) ? "Network connected, but Android has not validated internet. Trying native Maps; no validation-probe dependency."
                    : notifications != null && !notifications.areNotificationsEnabled()
                    ? "Downloading in the background. Notifications are disabled; use Android's Active apps to stop."
                    : "Downloading with a visible background import notification.";
            doingWork = true;
            SavedStore.flush();
            for (SavedStore.Place p : batch) {
                synchronized (SavedStore.class) {
                    if (SavedStore.enrichmentPaused || pauseRequested || !EnrichmentService.running()) {
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
            pauseRequested = true;
            String reason = "Import paused after " + e.getClass().getSimpleName() + ". Your entries are retained; tap Resume.";
            synchronized (SavedStore.class) {
                SavedStore.enrichmentPaused = true;
                SavedStore.enrichmentPauseReason = SavedStore.enrichmentLastFailure = reason;
                try { SavedStore.save(c); }
                catch (IllegalStateException writeFailure) {
                    android.util.Log.e("UA", "Paused queue could not be checkpointed; original store retained", writeFailure);
                }
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(c, "Enrichment paused after an error. Entries are retained; use Retry.", android.widget.Toast.LENGTH_LONG).show());
        } finally {
            doingWork = false;
            publishProgress();
            boolean paused;
            synchronized (SavedStore.class) {
                paused = SavedStore.enrichmentPaused || pauseRequested;
            }
            synchronized (RUN) {
                long next = RUN.finish(paused, delay);
                if (next >= 0) scheduled = WORKER.schedule(() -> start(c), next, TimeUnit.MILLISECONDS);
            }
        }
    }

    static boolean networkAvailable(Context c) {
        ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    private static boolean networkValidated(Context c) {
        ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static String networkWaitingReason(Context c) {
        ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                ? "Waiting for network sign-in (captive portal). No place retries used."
                : "Waiting for an internet-capable network. No retry attempts are used while disconnected.";
    }

    private static boolean needsNetwork(Context c, SavedStore.Place p) {
        return metadataWaiting(p) || !p.photo().isEmpty() && !Thumbs.cached(c, p.photo());
    }

    private static void enrich(Context c, SavedStore.Place target) {
        boolean thumbnailPhase = !metadataWaiting(target);
        boolean metadataAttempted = false, thumbnailAttempted = false;
        long networkVersion = NETWORK_CHANGES.get(), serviceVersion = SERVICE_STOPS.get();
        try {
            if (needsNetwork(c, target) && !networkAvailable(c)) {
                waitForNetwork(c, target);
                return;
            }
            if (!thumbnailPhase) {
                SavedStore.Place identity;
                synchronized (SavedStore.class) { identity = target.displayCopy(); }
                if (identity.ftid == null && identity.cid == null && identity.placeId == null && SavedStore.isShortMapsLink(identity.sourceUrl)) {
                    synchronized (SavedStore.class) { target.attempts++; metadataAttempted = true; }
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
                if (!NativePlaces.available()) {
                    synchronized (SavedStore.class) {
                        target.detailsState = "pending";
                        target.retryAt = 0;
                        if (metadataAttempted) target.attempts = Math.max(0, target.attempts - 1);
                    }
                    waitingReason = "Waiting for Maps' place service; no retry attempt used.";
                    return;
                }
                if (!metadataAttempted) synchronized (SavedStore.class) { target.attempts++; metadataAttempted = true; }
                SavedStore.Place details = NativePlaces.lookup(c, feature);
                synchronized (SavedStore.class) {
                    if (!"loading".equals(target.detailsState) || SavedStore.find(target.id) == null) return;
                    if (SavedStore.enrichmentPaused || pauseRequested || serviceVersion != SERVICE_STOPS.get()) {
                        target.detailsState = "pending";
                        target.attempts = Math.max(0, target.attempts - 1);
                        return;
                    }
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
            synchronized (SavedStore.class) {
                if (SavedStore.enrichmentPaused || pauseRequested || serviceVersion != SERVICE_STOPS.get()) return;
            }
            if (target.photo().isEmpty()) {
                synchronized (SavedStore.class) { target.thumbnailState = "unavailable"; }
            } else {
                if (!Thumbs.cached(c, target.photo()) && !networkAvailable(c)) { waitForNetwork(c, target); return; }
                synchronized (SavedStore.class) { target.thumbnailAttempts++; thumbnailAttempted = true; }
                String thumbnail = prefetchRepresentative(c, target);
                synchronized (SavedStore.class) {
                    if (!"cancelled".equals(target.thumbnailState) && !pauseRequested &&
                            !SavedStore.enrichmentPaused && serviceVersion == SERVICE_STOPS.get()) {
                        target.thumbnailUrl = thumbnail;
                        target.thumbnailState = "ready";
                    }
                }
            }
            synchronized (SavedStore.class) {
                target.retryAt = 0; target.thumbnailError = ""; SavedStore.enrichmentLastFailure = "";
            }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            synchronized (SavedStore.class) {
                if ("cancelled".equals(target.detailsState) || "cancelled".equals(target.thumbnailState) ||
                        !thumbnailPhase && !"loading".equals(target.detailsState)) return;
                boolean disconnected = !networkAvailable(c) || networkVersion != NETWORK_CHANGES.get();
                boolean stopped = pauseRequested || SavedStore.enrichmentPaused || serviceVersion != SERVICE_STOPS.get();
                if (!(root instanceof IdentityReview) && (disconnected || stopped)) {
                    if (metadataAttempted && !"complete".equals(target.detailsState)) target.attempts = Math.max(0, target.attempts - 1);
                    if (thumbnailAttempted) target.thumbnailAttempts = Math.max(0, target.thumbnailAttempts - 1);
                    if (disconnected) {
                        waitForNetwork(c, target);
                        if (networkAvailable(c)) waitingReason = "Connection changed; resuming saved work without using a retry.";
                    } else {
                        if (metadataWaiting(target)) target.detailsState = "pending";
                        target.retryAt = 0;
                    }
                    return;
                }
                if (root instanceof NativePlaces.ServiceUnavailable) {
                    target.detailsState = "pending";
                    if (metadataAttempted) target.attempts = Math.max(0, target.attempts - 1);
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
                SavedStore.enrichmentLastFailure = safeMessage;
                if (thumbnailPhase && "complete".equals(target.detailsState) &&
                        target.metadataAt > 0 && System.currentTimeMillis() - target.metadataAt > TimeUnit.DAYS.toMillis(1) &&
                        target.attempts < MAX_ATTEMPTS) target.detailsState = "pending";
            }
            android.util.Log.w("UA", "Import entry remains unresolved: " + root.getClass().getSimpleName());
        }
    }

    private static void waitForNetwork(Context c, SavedStore.Place p) {
        synchronized (SavedStore.class) {
            if (metadataWaiting(p)) p.detailsState = "pending";
            else if (!p.photo().isEmpty() && !"ready".equals(p.thumbnailState) && !"cancelled".equals(p.thumbnailState))
                p.thumbnailState = "pending";
            p.retryAt = 0;
        }
        waitingReason = networkWaitingReason(c);
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
