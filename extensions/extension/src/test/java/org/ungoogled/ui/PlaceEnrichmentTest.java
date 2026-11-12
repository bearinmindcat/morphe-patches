package org.ungoogled.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class PlaceEnrichmentTest extends LibraryTestSupport {
    @Test public void unavailableMetadataFinishesDecodedNativeThumbnailWithoutRetryingMetadata() throws Exception {
        SavedStore.Place p = new SavedStore.Place();
        p.name = "Native photo already known";
        p.detailsState = "unavailable";
        p.thumbnailState = "pending";
        String available = "https://lh3.googleusercontent.com/test-only-offline-image";
        p.photos.add(available);
        Method path = Thumbs.class.getDeclaredMethod("file", Context.class, String.class);
        path.setAccessible(true);
        File cached = (File) path.invoke(null, context, Thumbs.sized(available, Thumbs.SIZE));
        assertTrue(cached.getParentFile().isDirectory() || cached.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(cached)) {
            assertTrue(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        p.photos.add(0, "https://example.invalid/unsupported-first-photo");
        SavedStore.places.put(p.key(), p);
        SavedStore.enrichmentPaused = false;
        drain();
        assertEquals("unavailable", p.detailsState);
        assertEquals("ready", p.thumbnailState);
        assertEquals(available, p.thumbnailUrl);
        assertEquals(0, p.attempts);
        assertEquals(1, p.thumbnailAttempts);
        assertFalse(waiting(p));
        assertTrue(Thumbs.cached(context, p.photo()));
        reload();
        assertEquals("ready", SavedStore.find(p.id).thumbnailState);
    }

    @Test public void pausedBatchDoesNotConsumeAttemptsAndQueueStateSurvivesRestart() throws Exception {
        for (int i = 0; i < 5; i++) {
            SavedStore.Place p = new SavedStore.Place();
            p.detailsState = "pending";
            SavedStore.places.put(p.key(), p);
        }
        SavedStore.enrichmentPaused = true;
        drain();
        assertTrue(SavedStore.places.values().stream().allMatch(p -> p.attempts == 0));
        PlaceEnrichment.pause(context, true);
        reload();
        assertTrue(SavedStore.enrichmentPaused);
        assertEquals(5, SavedStore.places.size());
        PlaceEnrichment.cancel(context);
        reload();
        assertTrue(SavedStore.places.values().stream().allMatch(p -> "cancelled".equals(p.detailsState)));
        PlaceEnrichment.retry(context, null);
        reload();
        assertFalse(SavedStore.enrichmentPaused);
        assertTrue(SavedStore.places.values().stream().allMatch(p -> "pending".equals(p.detailsState) && p.attempts == 0));
    }

    @Test public void terminalIdentityReviewDoesNotRestartMetadataWhenThumbnailFails() throws Exception {
        SavedStore.Place p = new SavedStore.Place();
        p.name = "Review retained";
        p.detailsState = "review";
        p.detailError = "Unverified replacement";
        p.thumbnailState = "pending";
        p.thumbnailAttempts = 4;
        p.photos.add("https://example.invalid/not-a-supported-thumbnail");
        SavedStore.places.put(p.key(), p);
        SavedStore.enrichmentPaused = false;
        drain();
        assertEquals("review", p.detailsState);
        assertEquals("Unverified replacement", p.detailError);
        assertEquals(0, p.attempts);
        assertEquals("failed", p.thumbnailState);
        assertEquals(5, p.thumbnailAttempts);
        assertFalse(waiting(p));
        PlaceEnrichment.retry(context, null);
        assertEquals("review", p.detailsState);
    }

    @Test public void unsupportedIdentifiersAreRetainedWithoutConsumingNativeRequests() throws Exception {
        SavedStore.Place p = SavedStore.fromMapsLink("https://www.google.com/maps/search/?query_place_id=ChIJ42", "Opaque");
        p.detailsState = "loading";
        SavedStore.places.put(p.key(), p);
        Method enrich = PlaceEnrichment.class.getDeclaredMethod("enrich", Context.class, SavedStore.Place.class);
        enrich.setAccessible(true);
        worker(() -> { enrich.invoke(null, context, p); return null; });
        assertEquals("unavailable", p.detailsState);
        assertEquals(0, p.attempts);
        assertEquals("ChIJ42", p.placeId);
        assertFalse(p.located());
        p.placeId = null;
        p.cid = "invalid";
        p.detailsState = "loading";
        worker(() -> { enrich.invoke(null, context, p); return null; });
        assertEquals("unavailable", p.detailsState);
        assertEquals(0, p.attempts);
        assertEquals("invalid", p.cid);
    }

    @Test public void replacementConfirmationCannotOverwriteAnExistingEntryOrItsNotes() throws Exception {
        SavedStore.Place original = SavedStore.fromMapsLink("https://maps.google.com/?cid=41", "Old source");
        SavedStore.Place other = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Other entry");
        other.note = "Keep this private note";
        SavedStore.keep(context, original);
        SavedStore.keep(context, other);
        original.candidateFtid = "0x123:0x2a";
        original.candidateName = "Proposed";
        original.detailsState = "review";
        assertThrows(IllegalStateException.class, () -> SavedStore.confirmReplacement(context, original, "0x123:0x2b"));
        assertEquals("41", original.cid);
        SavedStore.confirmReplacement(context, original, "0x123:0x2a");
        assertEquals(2, SavedStore.places.size());
        assertEquals("Keep this private note", SavedStore.find(other.id).note);
        assertEquals("https://maps.google.com/?cid=41", SavedStore.find(original.id).sourceUrl);
        assertEquals("42", original.cid);
        assertEquals("pending", original.detailsState);
        assertFalse(original.located());
    }

    @Test public void thumbnailRequestsRejectCredentialsKeysAndNonImageDestinations() {
        for (String url : new String[]{"http://lh3.googleusercontent.com/photo", "https://example.com/photo",
                "https://user:password@lh3.googleusercontent.com/photo", "https://maps.googleapis.com/photo?key=secret",
                "https://lh3.googleusercontent.com:1234/photo", "https://lh3.googleusercontent.com.evil.invalid/photo"})
            assertNull(url, Thumbs.sized(url, Thumbs.SIZE));
    }

    @Test public void interruptedThumbnailWriteRecoversItsDecodedBytesOffline() throws Exception {
        String url = "https://example.invalid/offline-recovery";
        Method path = Thumbs.class.getDeclaredMethod("file", Context.class, String.class);
        path.setAccessible(true);
        File cached = (File) path.invoke(null, context, url);
        assertTrue(cached.getParentFile().isDirectory() || cached.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(cached + ".bak"))) {
            assertTrue(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        java.nio.file.Files.write(cached.toPath(), new byte[]{1, 2, 3});
        Method fetch = Thumbs.class.getDeclaredMethod("fetch", Context.class, String.class);
        fetch.setAccessible(true);
        Bitmap recovered = worker(() -> (Bitmap) fetch.invoke(null, context, url));
        assertEquals(2, recovered.getWidth());
        assertFalse(new File(cached + ".bak").exists());
        assertTrue(cached.length() > 3);
    }

    private void drain() throws Exception {
        Method drain = PlaceEnrichment.class.getDeclaredMethod("drain", Context.class);
        drain.setAccessible(true);
        worker(() -> { drain.invoke(null, context); return null; });
    }

    private static boolean waiting(SavedStore.Place p) throws Exception {
        Method waiting = PlaceEnrichment.class.getDeclaredMethod("waiting", SavedStore.Place.class);
        waiting.setAccessible(true);
        return (boolean) waiting.invoke(null, p);
    }
}
