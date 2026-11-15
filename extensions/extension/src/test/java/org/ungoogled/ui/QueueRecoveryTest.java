package org.ungoogled.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.io.IOException;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = QueueRecoveryTest.NativeBridge.class)
public class QueueRecoveryTest extends LibraryTestSupport {
    @Before public void prepareQueue() {
        NativeBridge.requests = 0;
        NativeBridge.onRequest = null;
        NativeBridge.result = null;
        SavedStore.enrichmentPaused = false;
        online(context, true);
    }

    @Test public void disconnectedMetadataAndThumbnailWorkDoNotConsumeAttempts() throws Exception {
        online(context, false);
        SavedStore.Place metadata = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Retained");
        metadata.detailsState = "loading";
        metadata.attempts = 2;
        SavedStore.places.put(metadata.key(), metadata);
        SavedStore.Place thumbnail = new SavedStore.Place();
        thumbnail.name = "Review retained";
        thumbnail.detailsState = "review";
        thumbnail.candidateFtid = "0x123:0x2a";
        thumbnail.thumbnailState = "pending";
        thumbnail.thumbnailAttempts = 3;
        thumbnail.photos.add("https://example.invalid/not-a-native-photo");
        SavedStore.places.put(thumbnail.key(), thumbnail);
        for (int i = 0; i < 10; i++) {
            enrich(metadata);
            enrich(thumbnail);
        }
        assertEquals(0, NativeBridge.requests);
        assertEquals(2, metadata.attempts);
        assertEquals(3, thumbnail.thumbnailAttempts);
        assertEquals("pending", metadata.detailsState);
        assertEquals("review", thumbnail.detailsState);
        assertEquals("0x123:0x2a", thumbnail.candidateFtid);
        assertEquals("pending", thumbnail.thumbnailState);
        assertEquals(0, metadata.retryAt);
    }

    @Test public void networkLossDuringNativeUnknownResponseRefundsTheAttempt() throws Exception {
        SavedStore.Place p = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Retained");
        p.detailsState = "loading";
        p.attempts = 4;
        SavedStore.places.put(p.key(), p);
        NativeBridge.onRequest = () -> online(context, false);
        enrich(p);
        assertEquals(1, NativeBridge.requests);
        assertEquals(4, p.attempts);
        assertEquals("pending", p.detailsState);
        assertEquals(0, p.retryAt);
        assertEquals("https://maps.google.com/?cid=42", p.sourceUrl);
    }

    @Test public void connectedNativeFailuresStillHaveABoundedRetryBudget() throws Exception {
        SavedStore.Place p = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Retained");
        p.detailsState = "loading";
        p.attempts = 4;
        SavedStore.places.put(p.key(), p);
        enrich(p);
        assertEquals(1, NativeBridge.requests);
        assertEquals(5, p.attempts);
        assertEquals("failed", p.detailsState);
        assertTrue(p.detailError.contains("UNKNOWN"));
    }

    @Test public void reconnectBeforeFailureDoesNotHideTheInterruptedConnection() throws Exception {
        SavedStore.Place p = pendingPlace();
        NativeBridge.onRequest = () -> PlaceEnrichment.networkChanged(context, true);
        enrich(p);
        assertEquals(1, NativeBridge.requests);
        assertEquals(4, p.attempts);
        assertEquals("pending", p.detailsState);
        assertEquals(0, p.retryAt);
    }

    @Test public void cancellationDuringLookupCannotApplyTheLateResult() throws Exception {
        SavedStore.Place p = pendingPlace();
        p.note = "Retained note";
        NativeBridge.result = details();
        NativeBridge.onRequest = () -> PlaceEnrichment.cancel(context);
        enrich(p);
        assertEquals("cancelled", p.detailsState);
        assertEquals("Retained note", p.note);
        assertFalse(p.located());
        assertEquals("", p.photo());
        assertEquals(1, SavedStore.places.size());
    }

    @Test public void pauseDuringLookupPreventsTheNextThumbnailRequest() throws Exception {
        SavedStore.Place p = pendingPlace();
        NativeBridge.result = details();
        NativeBridge.result.photos.add("https://example.invalid/never-request-this");
        NativeBridge.onRequest = () -> PlaceEnrichment.pause(context, true);
        enrich(p);
        assertTrue(SavedStore.enrichmentPaused);
        assertEquals(0, p.thumbnailAttempts);
        assertNotEquals("failed", p.thumbnailState);
    }

    @Test public void stoppedServiceDoesNotSpendANativeFailureAttempt() throws Exception {
        SavedStore.Place p = pendingPlace();
        NativeBridge.onRequest = PlaceEnrichment::serviceStopped;
        enrich(p);
        assertEquals(1, NativeBridge.requests);
        assertEquals(4, p.attempts);
        assertEquals("pending", p.detailsState);
    }

    @Test public void aReturnedIdentityMismatchStaysInReviewAcrossAConnectionChange() throws Exception {
        SavedStore.Place p = pendingPlace();
        NativeBridge.result = details();
        NativeBridge.result.ftid = "0x123:0x2b";
        NativeBridge.onRequest = () -> PlaceEnrichment.networkChanged(context, true);
        enrich(p);
        assertEquals("review", p.detailsState);
        assertEquals("0x123:0x2b", p.candidateFtid);
        assertEquals("42", p.cid);
        assertFalse(p.located());
    }

    @Test @Config(shadows = {NativeBridge.class, PatchedProfile.class})
    public void reconnectWakesAnExistingServiceWithoutReopeningOrReimporting() throws Exception {
        SavedStore.Place p = pendingPlace();
        p.detailsState = "pending";
        NativeBridge.result = details();
        online(context, false);
        org.robolectric.android.controller.ServiceController<EnrichmentService> service =
                Robolectric.buildService(EnrichmentService.class).create();
        try {
            PlaceEnrichment.start(context);
            awaitQueue();
            assertFalse(PlaceEnrichment.isForeground());
            assertEquals(0, NativeBridge.requests);
            assertEquals(4, p.attempts);
            assertTrue(PlaceEnrichment.progress().contains("Waiting for"));
            online(context, true);
            PlaceEnrichment.networkChanged(context, false);
            awaitQueue();
            assertEquals(1, NativeBridge.requests);
            assertEquals("complete", p.detailsState);
            assertEquals("unavailable", p.thumbnailState);
            assertEquals(1, SavedStore.places.size());
            assertFalse(PlaceEnrichment.isForeground());
        } finally { service.destroy(); }
    }

    @Test public void aConnectedNetworkDoesNotDependOnUnblockedValidationProbes() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(manager).setNetworkCapabilities(manager.getActiveNetwork(), capabilities);
        assertTrue(PlaceEnrichment.networkAvailable(context));
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        assertFalse(PlaceEnrichment.networkAvailable(context));
    }

    @Test public void aResumeSignalWinsEvenIfTheFinishingDrainSawAnOlderPause() {
        PlaceEnrichment.RunState state = new PlaceEnrichment.RunState();
        assertTrue(state.request());
        assertFalse(state.request());
        assertFalse(state.request());
        assertEquals(0, state.finish(true, -1));
        assertTrue(state.request());
        assertEquals(30000, state.finish(false, 30000));
        assertTrue(state.request());
        assertEquals(-1, state.finish(true, 30000));
    }

    @Test public void completedImportKeepsTheServiceUntilItsQueuedWorkIsInspected() throws Exception {
        java.lang.reflect.Field imports = PlaceEnrichment.class.getDeclaredField("IMPORTS");
        imports.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicInteger) imports.get(null)).set(1);
        set(PlaceEnrichment.class, "serviceIdle", true);
        assertFalse(PlaceEnrichment.shouldStopService());
        PlaceEnrichment.finishImport(context);
        assertFalse(PlaceEnrichment.shouldStopService());
    }

    private SavedStore.Place pendingPlace() {
        SavedStore.Place p = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Retained");
        p.detailsState = "loading";
        p.attempts = 4;
        SavedStore.places.put(p.key(), p);
        return p;
    }

    private static SavedStore.Place details() {
        SavedStore.Place details = new SavedStore.Place();
        details.ftid = "0x123:0x2a";
        details.name = "Native title";
        details.lat = 1;
        details.lng = 2;
        return details;
    }

    private void enrich(SavedStore.Place p) throws Exception {
        Method enrich = PlaceEnrichment.class.getDeclaredMethod("enrich", Context.class, SavedStore.Place.class);
        enrich.setAccessible(true);
        worker(() -> { enrich.invoke(null, context, p); return null; });
        awaitControls();
    }

    static void online(Context context, boolean connected) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        org.robolectric.shadows.ShadowConnectivityManager shadow = Shadows.shadowOf(manager);
        shadow.setActiveNetworkInfo(connected ? ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true) : null);
        if (connected) {
            NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
            Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            shadow.setNetworkCapabilities(manager.getActiveNetwork(), capabilities);
        }
    }

    @Implements(value = NativePlaces.class, isInAndroidSdk = false)
    public static class NativeBridge {
        static int requests;
        static Runnable onRequest;
        static SavedStore.Place result;

        @Implementation protected static boolean available() { return true; }

        @Implementation protected static SavedStore.Place lookup(Context context, String featureId) throws IOException {
            requests++;
            if (onRequest != null) onRequest.run();
            if (result != null) return result;
            throw new IOException("Placemark fetch returned with errorCode: UNKNOWN");
        }
    }

    @Implements(value = Shapes.class, isInAndroidSdk = false)
    public static class PatchedProfile {
        @Implementation protected static boolean localSavedPatched() { return true; }
    }
}
