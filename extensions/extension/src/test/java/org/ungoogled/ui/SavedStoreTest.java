package org.ungoogled.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class SavedStoreTest extends LibraryTestSupport {
    @Test public void retainsQuotedMetadataAndEveryUnresolvedRowWithoutInventingCoordinates() throws Exception {
        String csv = "\uFEFFTitle,Note,URL,Tags,Comment\r\n"
                + "\"Caf\u00e9, \u6771\u4eac\",\"line 1\nline \"\"2\"\"\",\"https://maps.google.com/?cid=42\",food,comment\r\n"
                + "Missing link,keep me,,,still here\r\n"
                + "Bad latitude,,\"https://www.google.com/maps/search/?query=95,12\",,\r\n"
                + "Viewport,,\"https://www.google.com/maps/@48.7,2.1,10z\",,\r\n"
                + "Zero,,\"https://www.google.com/maps/search/?query=0,0\",,\r\n";
        SavedStore.importText(context, csv, "Food.csv");
        assertEquals(5, SavedStore.places.size());
        SavedStore.Place quoted = SavedStore.find("cid:42");
        String list = quoted.lists.iterator().next();
        assertEquals("Caf\u00e9, \u6771\u4eac", quoted.name);
        assertEquals("line 1\nline \"2\"\ncomment", quoted.listNote(list));
        assertEquals("", quoted.note);
        assertEquals("food", quoted.sources.get(0).getJSONArray("values").getString(3));
        assertEquals(1, SavedStore.allSaved().stream().filter(SavedStore.Place::located).count());
        SavedStore.Place zero = SavedStore.find("0.000000,0.000000");
        assertNotNull(zero);
        assertTrue(SavedStore.Place.fromJson(zero.toJson()).located());
        SavedStore.importText(context, csv, "Food.csv");
        assertEquals(5, SavedStore.places.size());
        assertEquals(1, quoted.sources.size());
    }

    @Test public void keepsDuplicateRowsAndIdentifierKindsDistinct() throws Exception {
        SavedStore.importText(context, "Title,Note,URL\nA,first,https://maps.google.com/?cid=42\n"
                + "Same title,opaque,https://www.google.com/maps/search/?query_place_id=ChIJ42\n"
                + "A,second,https://www.google.com/maps/place/X/data=!1s0x123:0x2a\n", "Ids.csv");
        assertEquals(3, SavedStore.places.size());
        assertNotNull(SavedStore.find("place:ChIJ42"));
        assertFalse(SavedStore.fromMapsLink("https://www.google.com/maps/place/X/@1,2,5z/data=!1s0x1:0x2", "X").located());
        assertFalse(SavedStore.fromMapsLink("https://maps.google.com/?cid=0", "Unavailable").located());
        assertEquals("42", SavedStore.fromMapsLink("https://maps.google.com/?ftid=0x0:0x2a", "CID").cid);
        assertNull(SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "CID").ftid);
    }

    @Test public void onlyDecodesCompleteTypedPlaceIdEnvelopes() {
        String id = "ChIJLU7jZClu5kcR4PcOOO6p3I0";
        assertEquals("0x47e66e2964e34e2d:0x8ddca9ee380ef7e0", NativePlaces.featureFromPlaceId(id));
        assertNull(NativePlaces.featureFromPlaceId("10222232094831998944"));
        assertNull(NativePlaces.featureFromPlaceId("0x47e66e2964e34e2d:0x8ddca9ee380ef7e0"));
        assertNull(NativePlaces.featureFromPlaceId("ChIJ42"));
        assertNull(NativePlaces.featureFromPlaceId(id + "invalid"));
        SavedStore.Place p = SavedStore.fromMapsLink("https://www.google.com/maps/search/?query_place_id=" + id, "Original");
        assertEquals(id, p.placeId);
        assertNull(p.ftid);
        assertNull(p.cid);
        assertFalse(p.located());
    }

    @Test public void imports1000PlacesAndOverlappingListsFromZipThenRecoversOffline() throws Exception {
        StringBuilder first = new StringBuilder("Title,Note,URL\n"), second = new StringBuilder("Title,Note,URL\n");
        for (int i = 0; i < 1000; i++) {
            String url = "\"https://www.google.com/maps/search/?query=" + (10 + i * .01) + ",20\"";
            first.append("Place ").append(i).append(",first-note,").append(url).append('\n');
            if (i < 10) second.append("Other title ").append(i).append(",second-note,").append(url).append('\n');
        }
        File zip = new File(context.getCacheDir(), "takeout.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (String[] entry : new String[][]{{"Takeout/Saved/First.csv", first.toString()}, {"Takeout/Saved/Second.csv", second.toString()}}) {
                out.putNextEntry(new ZipEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        SavedStore.Imported result = worker(() -> SavedStore.importFiles(context, List.of(Uri.fromFile(zip))));
        assertTrue(result.problems.toString(), result.problems.isEmpty());
        assertEquals(2, result.files);
        assertEquals(1000, result.places);
        assertEquals(1010, SavedStore.places.values().stream().mapToInt(p -> p.lists.size()).sum());
        String secondId = listNamed("Second");
        for (SavedStore.Place p : SavedStore.inList(secondId)) {
            assertEquals("second-note", p.listNote(secondId));
            assertTrue(p.listTitle(secondId).startsWith("Other title "));
        }
        assertTrue(SavedStore.places.values().stream().allMatch(p -> p.note.isEmpty()));
        reload();
        assertEquals(1000, SavedStore.places.size());
        assertEquals(1010, SavedStore.places.values().stream().mapToInt(p -> p.sources.size()).sum());
        assertTrue(SavedStore.enrichmentPaused);
        result = worker(() -> SavedStore.importFiles(context, List.of(Uri.fromFile(zip))));
        assertEquals(0, result.places);
        assertEquals(1000, SavedStore.places.size());
    }

    @Test public void migratesV1AndV2WithoutLosingNotesEmojiOrOriginalFiles() throws Exception {
        for (int version : new int[]{1, 2}) {
            resetLibrary();
            String legacy = "{\"version\":" + version + ",\"lists\":[{\"id\":\"custom\",\"name\":\"Food\",\"emoji\":\"\\u2615\"}],"
                    + "\"places\":[{\"ftid\":\"0x0:0x2a\",\"name\":\"Place\",\"lat\":0,\"lng\":0,\"note\":\"private\","
                    + "\"lists\":[\"custom\"]}]}";
            File file = new File(context.getFilesDir(), SavedStore.FILE);
            Files.write(file.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
            set(SavedStore.class, "loaded", false);
            SavedStore.load(context);
            SavedStore.Place p = SavedStore.find("cid:42");
            assertFalse(p.located());
            assertEquals("", p.listNote("custom"));
            p.detailsState = "loading";
            p.attempts = 2;
            SavedStore.save(context);
            worker(() -> { SavedStore.flush(); return null; });
            assertEquals(legacy, new String(Files.readAllBytes(new File(context.getFilesDir(), SavedStore.FILE + ".v" + version + ".bak").toPath()), StandardCharsets.UTF_8));
            String id = p.id, membership = p.membershipId("custom");
            reload();
            p = SavedStore.find("cid:42");
            assertEquals(id, p.id);
            assertEquals(membership, p.membershipId("custom"));
            assertEquals("pending", p.detailsState);
            assertEquals(2, p.attempts);
            assertEquals("private", p.note);
            assertEquals("\u2615", SavedStore.emojis.get("custom"));
        }
    }

    @Test public void v3OpaqueMetadataSurvivesButCannotActivateImportedPhotoUrls() throws Exception {
        JSONObject place = new JSONObject().put("id", "entry").put("name", "Museum")
                .put("cid", "42").put("hasCoordinates", false)
                .put("photos", new JSONArray().put("https://lh3.googleusercontent.com/UNTRUSTED_SENTINEL"))
                .put("thumbnailUrl", "https://lh3.googleusercontent.com/UNTRUSTED_SENTINEL")
                .put("detailsState", "complete").put("thumbnailState", "ready").put("metadataAt", 123)
                .put("futureSourceData", new JSONObject().put("original", "retained"));
        SavedStore.importText(context, new JSONObject().put("version", 3).put("places", new JSONArray().put(place))
                .put("extraLibraryData", "retained").toString(), "backup.json");
        SavedStore.Place p = SavedStore.find("entry");
        assertEquals("", p.photo());
        assertEquals("none", p.detailsState);
        assertEquals(0, p.metadataAt);
        SavedStore.save(context);
        reload();
        p = SavedStore.find("entry");
        assertEquals("", p.photo());
        assertEquals("retained", p.toJson().getJSONObject("futureSourceData").getString("original"));
        assertTrue(p.toJson().getJSONArray("sources").toString().contains("UNTRUSTED_SENTINEL"));
        assertEquals("retained", SavedStore.toJson().getString("extraLibraryData"));
    }

    @Test public void malformedAndNewerLibrariesCannotBeSilentlyOverwritten() throws Exception {
        for (String original : new String[]{"{broken", "{\"version\":99,\"places\":[]}"}) {
            resetLibrary();
            File file = new File(context.getFilesDir(), SavedStore.FILE);
            Files.write(file.toPath(), original.getBytes(StandardCharsets.UTF_8));
            set(SavedStore.class, "loaded", false);
            assertThrows(IllegalStateException.class, () -> SavedStore.load(context));
            assertThrows(IllegalStateException.class, () -> SavedStore.save(context));
            assertEquals(original, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test public void failedDocumentRollsBackItsPartialRowsAndKeepsOtherValidFiles() throws Exception {
        File good = new File(context.getCacheDir(), "Good.csv"), bad = new File(context.getCacheDir(), "Bad.json");
        Files.write(good.toPath(), "Title,Note,URL\nMissing,keep me,\n".getBytes(StandardCharsets.UTF_8));
        Files.write(bad.toPath(), "{\"places\":[{\"name\":\"partial\",\"lat\":1,\"lng\":2},false]}".getBytes(StandardCharsets.UTF_8));
        SavedStore.Imported result = worker(() -> SavedStore.importFiles(context, List.of(Uri.fromFile(good), Uri.fromFile(bad))));
        assertEquals(1, result.places);
        assertEquals(1, result.files);
        assertEquals(1, result.problems.size());
        reload();
        SavedStore.Place p = SavedStore.allSaved().get(0);
        assertEquals("Missing", p.name);
        assertEquals("unavailable", p.detailsState);
        assertTrue(p.detailError.contains("retained"));
        assertFalse(p.located());
    }

    @Test public void invalidCoordinatesAndHistoryDoNotPartiallyMutateTheLibrary() throws Exception {
        SavedStore.importText(context, "Title,Note,URL\nKept,note,\n", "Kept.csv");
        SavedStore.Place heldByWorker = SavedStore.allSaved().get(0);
        heldByWorker.detailsState = "loading";
        String before = SavedStore.toJson().toString();
        for (String document : new String[]{
                "{\"places\":[{\"name\":\"bad\",\"lat\":91,\"lng\":0}]}",
                "{\"places\":[{\"name\":\"bad\",\"lat\":1}]}",
                "{\"version\":4,\"places\":[]}",
                "{\"version\":3.5,\"places\":[]}",
                "{\"version\":\"unknown\",\"places\":[]}",
                "{\"lists\":false}",
                "{\"places\":[{\"name\":\"partial\",\"lat\":1,\"lng\":2}],\"history\":[false]}",
                "Title,Note,URL\n\"unterminated,note,\n"}) {
            assertThrows(Exception.class, () -> SavedStore.importText(context, document, "Bad.csv"));
            assertEquals(before, SavedStore.toJson().toString());
            assertSame(heldByWorker, SavedStore.find(heldByWorker.id));
            assertEquals("loading", heldByWorker.detailsState);
        }
    }

    @Test public void importedPhotosStayInertInAliasesAndHistoryWhileNativePhotosSurvive() throws Exception {
        String nativePhoto = "https://lh3.googleusercontent.com/NATIVE_SENTINEL";
        SavedStore.Place nativePlace = SavedStore.fromMapsLink("https://maps.google.com/?cid=43", "Native");
        nativePlace.photos.add(nativePhoto);
        SavedStore.keep(context, nativePlace);
        JSONObject photo = new JSONObject().put("name", "Imported").put("lat", 1).put("lng", 2)
                .put("photo", "http://127.0.0.1/UNTRUSTED_SENTINEL")
                .put("photos", new JSONArray().put("https://lh3.googleusercontent.com/UNTRUSTED_SENTINEL"));
        JSONObject document = new JSONObject().put("home", photo).put("work", photo)
                .put("labels", new JSONArray().put(new JSONObject().put("label", "Alias").put("place", photo)))
                .put("history", new JSONArray().put(new JSONObject(photo.toString()).put("viewed", 1)));
        SavedStore.importText(context, document.toString(), "photos.json");
        assertEquals("", SavedStore.home.photo());
        assertEquals("", SavedStore.work.photo());
        assertEquals("", SavedStore.labels.get("Alias").photo());
        assertTrue(HistoryStore.newestFirst().get(0).photos.isEmpty());
        SavedStore.save(context);
        reload();
        set(HistoryStore.class, "loaded", false);
        HistoryStore.load(context);
        assertTrue(HistoryStore.newestFirst().get(0).photos.isEmpty());
        assertEquals(nativePhoto, SavedStore.find(nativePlace.id).photo());
    }

    @Test public void kmlRetainsMissingCoordinatesNotesAndDuplicatePlacemarks() throws Exception {
        String kml = "<k:kml xmlns:k=\"http://www.opengis.net/kml/2.2\"><k:Document><k:Folder><k:name>Trip</k:name>"
                + "<k:Placemark><k:name>First</k:name><k:description>note one</k:description><k:Point><k:coordinates>0,0</k:coordinates></k:Point></k:Placemark>"
                + "<k:Placemark><k:name>Second</k:name><k:description>note two</k:description><k:Point><k:coordinates>0,0</k:coordinates></k:Point></k:Placemark>"
                + "<k:Placemark><k:name>Missing</k:name><k:description>retained</k:description></k:Placemark>"
                + "</k:Folder></k:Document></k:kml>";
        SavedStore.importText(context, kml, "trip.kml");
        assertEquals(3, SavedStore.places.size());
        assertEquals(2, SavedStore.allSaved().stream().filter(SavedStore.Place::located).count());
        assertTrue(SavedStore.inList(listNamed("Trip")).stream().allMatch(p -> !p.listNote(listNamed("Trip")).isEmpty()));
        assertThrows(Exception.class, () -> SavedStore.importText(context, kml.substring(0, kml.length() - 10), "broken.kml"));
        assertEquals(3, SavedStore.places.size());
    }

    @Test public void staleDisplayCopyCannotRemoveAnotherMembership() throws Exception {
        SavedStore.Place p = SavedStore.keep(context, SavedStore.fromMapsLink("geo:0,0", "Point"));
        String first = SavedStore.addList(context, "First", ""), second = SavedStore.addList(context, "Second", "");
        SavedStore.Place stale = p.displayCopy();
        SavedStore.setMembership(context, p, second, true);
        SavedStore.setMembership(context, stale, first, true);
        assertEquals(2, p.lists.size());
    }

    @Test public void failedCheckpointKeepsTheOriginalAndCanBeRetried() throws Exception {
        SavedStore.importText(context, "Title,Note,URL\nOriginal,note,\n", "Original.csv");
        SavedStore.save(context);
        worker(() -> { SavedStore.flush(); return null; });
        File store = new File(context.getFilesDir(), SavedStore.FILE);
        byte[] before = Files.readAllBytes(store.toPath());
        Context unwritable = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return store; }
            @Override public Context getApplicationContext() { return this; }
        };
        SavedStore.importText(context, "Title,Note,URL\nNew,note,\n", "New.csv");
        SavedStore.save(unwritable);
        assertThrows(IOException.class, () -> worker(() -> { SavedStore.flush(); return null; }));
        assertArrayEquals(before, Files.readAllBytes(store.toPath()));
        SavedStore.save(context);
        reload();
        assertEquals(2, SavedStore.places.size());
    }

    @Test public void newlyLocatedBackupEntriesStillSupportNotesLabelsAndUnsave() throws Exception {
        JSONObject entry = new JSONObject().put("id", "stable").put("name", "Unknown").put("hasCoordinates", false);
        SavedStore.importText(context, new JSONObject().put("version", 3).put("places", new JSONArray().put(entry)).toString(), "first.json");
        SavedStore.Place stale = SavedStore.find("stable").displayCopy();
        entry.put("hasCoordinates", true).put("lat", 0).put("lng", 0);
        SavedStore.importText(context, new JSONObject().put("version", 3).put("places", new JSONArray().put(entry)).toString(), "second.json");
        SavedStore.Place current = SavedStore.find("0.000000,0.000000");
        assertNotNull(current);
        assertEquals(1, SavedStore.places.size());
        SavedStore.setNote(context, stale, "Kept on the same entry");
        assertEquals("Kept on the same entry", current.note);
        current.separateEntry = true;
        assertTrue(SavedStore.same(SavedStore.aliasOf(current), current));
        current.separateEntry = false;
        SavedStore.unsave(context, stale);
        assertTrue(SavedStore.places.isEmpty());
    }

    @Test public void refreshedBackupPhotoUrlsRemainInertAndRetainBothSources() throws Exception {
        JSONObject entry = new JSONObject().put("id", "stable").put("name", "Unknown").put("hasCoordinates", false);
        for (String photo : new String[]{"https://lh3.googleusercontent.com/first", "https://lh3.googleusercontent.com/second"}) {
            entry.put("photos", new JSONArray().put(photo));
            SavedStore.importText(context, new JSONObject().put("version", 3).put("places", new JSONArray().put(entry)).toString(), "backup.json");
        }
        SavedStore.Place p = SavedStore.find("stable");
        assertEquals("", p.photo());
        assertEquals(2, p.sources.size());
        assertTrue(p.toJson().toString().contains("/first"));
        assertTrue(p.toJson().toString().contains("/second"));
    }

    @Test public void backupMergeKeepsLocalNoteAndRetainsIncomingOriginalAnnotation() throws Exception {
        SavedStore.Place p = new SavedStore.Place();
        p.name = "Place";
        p.note = "Local private note";
        SavedStore.keep(context, p);
        JSONObject incoming = p.toJson().put("note", "Incoming private note");
        SavedStore.importText(context, new JSONObject().put("version", 3).put("places", new JSONArray().put(incoming)).toString(), "backup.json");
        assertEquals("Local private note", p.note);
        assertTrue(p.toJson().getJSONArray("sources").toString().contains("Incoming private note"));
    }

    @Test public void malformedSavedFileInZipDoesNotDiscardLaterValidLists() throws Exception {
        File file = new File(context.getCacheDir(), "mixed.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (String[] entry : new String[][]{
                    {"Takeout/Saved/First.csv", "Title,Note,URL\nFirst,kept,\n"},
                    {"Takeout/Saved/Bad.csv", "Title,Note,URL\n\"unterminated"},
                    {"Takeout/Saved/Last.csv", "Title,Note,URL\nLast,also kept,\n"}}) {
                out.putNextEntry(new ZipEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        SavedStore.Imported result = worker(() -> SavedStore.importFiles(context, List.of(Uri.fromFile(file))));
        assertEquals(2, result.places);
        assertEquals(2, result.files);
        assertEquals(1, result.problems.size());
        assertTrue(result.problems.get(0).contains("Bad.csv"));
        assertEquals(1, SavedStore.countIn(listNamed("Last")));
    }

    @Test public void legacyExhaustedQueueRecoversOnceWithoutChangingPauseOrIdentityReview() throws Exception {
        String old = "{\"version\":3,\"enrichmentPaused\":true,\"lists\":[{\"id\":\"custom\",\"name\":\"Food\",\"emoji\":\"\\u2615\"}],"
                + "\"places\":[{\"id\":\"kept\",\"cid\":\"42\",\"name\":\"Place\",\"hasCoordinates\":false,"
                + "\"note\":\"private\",\"lists\":[\"custom\"],\"membershipIds\":{\"custom\":\"membership\"},"
                + "\"listNotes\":{\"custom\":\"list note\"},\"sources\":[{\"id\":\"original\",\"values\":[\"all columns\"]}],"
                + "\"detailsState\":\"failed\",\"attempts\":5,\"detailError\":\"Placemark fetch returned with errorCode: UNKNOWN\"},"
                + "{\"id\":\"cancelled\",\"name\":\"Cancel\",\"detailsState\":\"cancelled\"},"
                + "{\"id\":\"review\",\"name\":\"Review\",\"detailsState\":\"review\",\"candidateFtid\":\"0x1:0x2\"},"
                + "{\"id\":\"candidate\",\"name\":\"Candidate\",\"detailsState\":\"failed\",\"candidateFtid\":\"0x1:0x3\"},"
                + "{\"id\":\"photo\",\"name\":\"Photo\",\"detailsState\":\"complete\",\"thumbnailState\":\"failed\",\"thumbnailAttempts\":5},"
                + "{\"id\":\"cancelled-photo\",\"name\":\"Cancelled photo\",\"detailsState\":\"complete\",\"thumbnailState\":\"cancelled\"},"
                + "{\"id\":\"unavailable\",\"name\":\"Unavailable\",\"detailsState\":\"unavailable\"}]}";
        File file = new File(context.getFilesDir(), SavedStore.FILE);
        Files.write(file.toPath(), old.getBytes(StandardCharsets.UTF_8));
        set(SavedStore.class, "loaded", false);
        SavedStore.load(context);
        assertTrue(SavedStore.enrichmentPaused);
        SavedStore.Place p = SavedStore.find("kept");
        assertEquals("pending", p.detailsState);
        assertEquals(0, p.attempts);
        assertEquals("private", p.note);
        assertEquals("list note", p.listNote("custom"));
        assertEquals("membership", p.membershipId("custom"));
        assertEquals("original", p.sources.get(0).getString("id"));
        assertEquals("cancelled", SavedStore.find("cancelled").detailsState);
        assertEquals("review", SavedStore.find("review").detailsState);
        assertEquals("0x1:0x2", SavedStore.find("review").candidateFtid);
        assertEquals("review", SavedStore.find("candidate").detailsState);
        assertEquals("0x1:0x3", SavedStore.find("candidate").candidateFtid);
        assertEquals("pending", SavedStore.find("photo").thumbnailState);
        assertEquals(0, SavedStore.find("photo").thumbnailAttempts);
        assertEquals("cancelled", SavedStore.find("cancelled-photo").thumbnailState);
        assertEquals("unavailable", SavedStore.find("unavailable").detailsState);
        worker(() -> { SavedStore.flush(); return null; });
        assertEquals(old, new String(Files.readAllBytes(new File(context.getFilesDir(), SavedStore.FILE + ".queue-v0.bak").toPath()),
                StandardCharsets.UTF_8));
        p.detailsState = "failed";
        p.attempts = 5;
        SavedStore.save(context);
        reload();
        assertEquals("failed", SavedStore.find("kept").detailsState);
        assertEquals(5, SavedStore.find("kept").attempts);
    }

    @Test public void unsupportedQueueSchemaIsNotSilentlyDowngraded() throws Exception {
        String original = "{\"version\":3,\"enrichmentQueueVersion\":2,\"places\":[]}";
        File file = new File(context.getFilesDir(), SavedStore.FILE);
        Files.write(file.toPath(), original.getBytes(StandardCharsets.UTF_8));
        set(SavedStore.class, "loaded", false);
        assertThrows(IllegalStateException.class, () -> SavedStore.load(context));
        assertThrows(IllegalStateException.class, () -> SavedStore.save(context));
        assertEquals(original, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}

abstract class LibraryTestSupport {
    protected Context context;

    @Before public void resetLibrary() throws Exception {
        context = RuntimeEnvironment.getApplication();
        awaitControls();
        awaitQueue();
        try { worker(() -> { SavedStore.flush(); return null; }); }
        catch (IOException expectedAfterWriteFailure) { /* The failure test leaves the writer in an error state. */ }
        for (String name : new String[]{SavedStore.FILE, HistoryStore.FILE}) {
            for (String suffix : new String[]{"", ".bak", ".new", ".v1.bak", ".v2.bak", ".queue-v0.bak"})
                Files.deleteIfExists(new File(context.getFilesDir(), name + suffix).toPath());
        }
        for (Class<?> type : new Class<?>[]{SavedStore.class, HistoryStore.class}) {
            set(type, "loaded", false);
            set(type, "writeDisabled", false);
        }
        set(SavedStore.class, "generation", 0L);
        set(SavedStore.class, "persistedGeneration", 0L);
        set(SavedStore.class, "writeError", null);
        set(PlaceEnrichment.class, "pauseRequested", false);
        SavedStore.enrichmentPaused = true;
        SavedStore.enrichmentPauseReason = SavedStore.enrichmentLastFailure = "";
        SavedStore.load(context);
        HistoryStore.load(context);
    }

    @After public void finishWrites() throws Exception {
        awaitControls();
        awaitQueue();
        SavedStore.enrichmentPaused = true;
        try { worker(() -> { SavedStore.flush(); return null; }); }
        catch (IOException expectedAfterWriteFailure) { /* Asserted by the failure test itself. */ }
    }

    protected void reload() throws Exception {
        awaitControls();
        worker(() -> { SavedStore.flush(); return null; });
        set(SavedStore.class, "loaded", false);
        SavedStore.load(context);
    }

    protected static String listNamed(String name) {
        return SavedStore.listNames().entrySet().stream().filter(e -> name.equals(e.getValue())).findFirst().get().getKey();
    }

    protected static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    protected static void awaitControls() throws Exception { awaitEnrichment("CONTROLS"); }

    protected static void awaitQueue() throws Exception { awaitEnrichment("WORKER"); }

    private static void awaitEnrichment(String name) throws Exception {
        Field field = PlaceEnrichment.class.getDeclaredField(name);
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    protected static <T> T worker(Callable<T> work) throws Exception {
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(work).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw new AssertionError(e.getCause());
        } finally { executor.shutdownNow(); }
    }
}
