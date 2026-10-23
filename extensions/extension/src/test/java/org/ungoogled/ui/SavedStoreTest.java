package org.ungoogled.ui;

import android.content.Context;
import android.net.Uri;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SavedStoreTest {
    private Context context;

    @Before public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        for (String name : new String[]{SavedStore.FILE, SavedStore.FILE + ".tmp", HistoryStore.FILE, HistoryStore.FILE + ".tmp"}) {
            Files.deleteIfExists(new File(context.getFilesDir(), name).toPath());
        }
        resetLoaded(SavedStore.class);
        resetLoaded(HistoryStore.class);
        SavedStore.load(context);
        HistoryStore.load(context);
    }

    private static void resetLoaded(Class<?> type) throws Exception {
        Field f = type.getDeclaredField("loaded"); f.setAccessible(true); f.setBoolean(null, false);
    }

    private int importJson(String text) throws Exception {
        return SavedStore.importFile(context, input("places-test.json", text));
    }

    private Uri input(String name, String text) throws Exception {
        File input = new File(context.getCacheDir(), name);
        Files.writeString(input.toPath(), text);
        return Uri.fromFile(input);
    }

    private Uri zip(String... entries) throws Exception {
        File input = new File(context.getCacheDir(), "takeout.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(input.toPath()))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return Uri.fromFile(input);
    }

    private static final String CSV_HEADER = "Title,Note,URL,Tags,Comment\r\n";
    private static final String CSV_PLACE = "Cafe,,https://www.google.com/maps/place/Cafe/data=!1s0x1:0x2,,\r\n";
    private static final String GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[9,49]},\"properties\":{\"Title\":\"Geo cafe\"}}]}";

    /** Includes every mutable part of SavedStore so rollback must preserve more than place count. */
    private void seed() throws Exception {
        importJson("{\"places\":[{\"ftid\":\"0x1:0x2\",\"name\":\"Existing\",\"lat\":0,\"lng\":0,\"lists\":[\"starred\"]}],"
                + "\"lists\":[{\"id\":\"custom\",\"name\":\"My list\"}],\"hiddenOnMap\":[\"custom\"],"
                + "\"home\":{\"name\":\"My home\",\"lat\":48,\"lng\":8},\"work\":{\"name\":\"My work\",\"lat\":47,\"lng\":7},"
                + "\"labels\":[{\"label\":\"Gym\",\"place\":{\"name\":\"My gym\",\"lat\":46,\"lng\":6}}]}");
    }

    private byte[] savedBytes() throws Exception {
        return Files.readAllBytes(new File(context.getFilesDir(), SavedStore.FILE).toPath());
    }

    private void rejectUnchanged(Uri... inputs) throws Exception {
        rejectUnchanged("Expected rejection for " + Arrays.toString(inputs), inputs);
    }

    private void rejectUnchanged(String description, Uri... inputs) throws Exception {
        String before = SavedStore.toJson().toString();
        String historyBefore = HistoryStore.toJson().toString();
        byte[] disk = savedBytes();
        assertThrows(description, Exception.class, () -> SavedStore.importFiles(context, Arrays.asList(inputs)));
        assertEquals(before, SavedStore.toJson().toString());
        assertEquals(historyBefore, HistoryStore.toJson().toString());
        assertArrayEquals(disk, savedBytes());
    }

    @Test public void takeoutCsvPreservesUnlocatedFeatureIdsAndQuotedNotes() throws Exception {
        String csv = "\uFEFF" + CSV_HEADER + "\"Cafe, east\",\"Line 1\nLine \"\"2\"\"\","
                + "https://www.google.com/maps/place/Cafe/data=!1s0xA:0xB,,Comment\r\n";
        SavedStore.Imported result = SavedStore.importFiles(context, Collections.singletonList(input("Want to go.csv", csv)));
        assertEquals(1, result.places); assertEquals(1, result.files); assertEquals(0, result.lists); assertEquals(1, result.unlocated);
        SavedStore.Place place = SavedStore.find("0xa:0xb");
        assertNotNull(place); assertFalse(place.located());
        assertEquals("Cafe, east", place.name); assertEquals("Line 1\nLine \"2\"\nComment", place.note);
        assertTrue(place.lists.contains(SavedStore.WANT_TO_GO));
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertFalse(SavedStore.find("0xa:0xb").located());
        assertEquals(0, importJson(SavedStore.toJson().toString()));
        SavedStore.locate(context, "0xa:0xb", 48, 8);
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertTrue(SavedStore.find("0xa:0xb").located());
    }

    @Test public void csvDroppedPinsCidAndRecentPositionsRemainUsable() throws Exception {
        SavedStore.Place seen = SavedStore.Place.fromJson(new JSONObject("{\"ftid\":\"0x1:0x2\",\"name\":\"Seen\",\"lat\":48,\"lng\":8}"));
        HistoryStore.record(context, seen, HistoryStore.VIEWED);
        String csv = CSV_HEADER + CSV_PLACE
                + "Pin,Note,https://maps.google.com/?q=47,7,,Comment\r\n"
                + "Cid,,https://maps.google.com/?cid=15,,\r\n";
        SavedStore.Imported result = SavedStore.importFiles(context, Collections.singletonList(input("Favorite places.csv", csv)));
        assertEquals(3, result.places); assertEquals(1, result.unlocated);
        assertEquals(48, SavedStore.find("0x1:0x2").lat, 0);
        assertEquals("Note\nComment", SavedStore.find("47.000000,7.000000").note);
        assertTrue(SavedStore.find("0x0:0xf").lists.contains(SavedStore.FAVOURITES));
        SavedStore.locate(context, "0x4:0xf", 46, 6);
        assertNull(SavedStore.find("0x0:0xf")); assertTrue(SavedStore.find("0x4:0xf").located());
    }

    @Test public void csvInvalidCoordinatesAndRowsRollBackExistingObjects() throws Exception {
        seed();
        SavedStore.Place original = SavedStore.find("0x1:0x2");
        for (String coords : new String[]{"91,8", "48,181", "NaN,8", "Infinity,8", "1e309,8"}) {
            // Reverse row order means the existing place is changed before the invalid row is parsed.
            rejectUnchanged(input("New list.csv", CSV_HEADER + "Bad,,\"https://maps.google.com/?q=" + coords + "\",,\r\n"
                    + "Cafe,New note,https://maps.google.com/data=!1s0x1:0x2!3d48!4d8,,\r\n"));
        }
        rejectUnchanged(input("New list.csv", CSV_HEADER + "Bad,,https://maps.google.com/data=!1s0x1:0x2!3d91!4d8,,\r\n"));
        for (String csv : new String[]{"not a Takeout file", CSV_HEADER + "\"Unclosed", CSV_HEADER + "\"closed\"junk,,url,,",
                CSV_HEADER + "Short,row", CSV_HEADER + "Bad,,https://example.com/unknown,,"}) {
            rejectUnchanged(input("New list.csv", csv));
        }
        assertSame(original, SavedStore.find("0x1:0x2"));
        assertEquals("", original.note); assertFalse(original.located()); assertEquals(1, original.lists.size());
    }

    @Test public void takeoutZipImportsEverySupportedEntryAndIgnoresUnrelatedFiles() throws Exception {
        Uri archive = zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE,
                "Takeout/Maps/Saved Places.json", GEOJSON, "Takeout/Mail/mail.csv", "unrelated");
        SavedStore.Imported result = SavedStore.importFiles(context, Collections.singletonList(archive));
        assertEquals(2, result.places); assertEquals(1, result.lists); assertEquals(1, result.unlocated); assertEquals(2, result.files);
        assertTrue(SavedStore.lists.containsValue("Trips")); assertNotNull(SavedStore.find("49.000000,9.000000"));
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertEquals(2, SavedStore.places.size());
    }

    @Test public void badLaterZipEntriesRollBackEarlierCsvAndMetadata() throws Exception {
        seed();
        rejectUnchanged(zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE, "Takeout/Maps/Saved Places.json", "{\"unknown\":true}"));
        rejectUnchanged(zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE, "Takeout/Saved/Bad.csv", CSV_HEADER + "\"unclosed"));
        rejectUnchanged(zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE, "Takeout/Maps/Saved Places.json",
                "{\"features\":[{\"geometry\":{\"coordinates\":[8,91]}}]}"));
        rejectUnchanged(zip("Takeout/Mail/mail.csv", "unrelated"));
    }

    @Test public void truncatedAndCrcDamagedArchivesAreRejected() throws Exception {
        seed();
        Uri archive = zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE);
        File file = new File(archive.getPath());
        byte[] bytes = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), Arrays.copyOf(bytes, bytes.length - 22));
        rejectUnchanged(archive);
        archive = zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE);
        bytes = Files.readAllBytes(file.toPath());
        // Corrupt the CRC in the central directory while keeping the entry and directory readable.
        for (int i = 0; i < bytes.length - 20; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) { bytes[i + 16] ^= 1; break; }
        }
        Files.write(file.toPath(), bytes);
        rejectUnchanged(archive);
    }

    @Test public void multipleFilesCountAndMergeListsOnce() throws Exception {
        SavedStore.Imported result = SavedStore.importFiles(context, Arrays.asList(
                input("Want to go.csv", CSV_HEADER + CSV_PLACE), input("Trips.csv", CSV_HEADER + CSV_PLACE), input("geo.txt", GEOJSON)));
        assertEquals(3, result.files); assertEquals(2, result.places); assertEquals(1, result.lists); assertEquals(1, result.unlocated);
        assertEquals(2, SavedStore.find("0x1:0x2").lists.size());
    }

    @Test public void laterFileFailureDoesNotApplyEarlierSavedPlacesOrHistory() throws Exception {
        seed();
        rejectUnchanged(input("Trips.csv", CSV_HEADER + CSV_PLACE), input("backup.json", "{\"places\":[],"
                + "\"history\":[{\"ftid\":\"new\",\"name\":\"New\",\"lat\":48,\"lng\":8,\"viewed\":123}]}"),
                input("bad.json", "{\"places\":[false]}"));
        rejectUnchanged(input("Trips.csv", CSV_HEADER + CSV_PLACE), Uri.fromFile(new File(context.getCacheDir(), "missing-input.json")));
    }

    @Test public void unknownJsonMalformedContainersAndInvalidSourceSyntaxAreRejected() throws Exception {
        seed();
        for (String json : new String[]{"{}", "{\"unknown\":[]}", "{\"places\":{}}", "{\"lists\":null}", "{\"history\":{}}",
                "{\"labels\":[{\"label\":\"Gym\",\"place\":false}]}", "{\"hiddenOnMap\":[false]}",
                "{\"home\":false}", "{\"work\":{\"name\":\"invalid\"}}", "{\"features\":{}}", "{\"features\":[{}]}",
                "{\"features\":[{\"geometry\":{\"coordinates\":[8]}}]}",
                "{\"places\":[]} trailing", "{'places':[]}", "{places:[]}", "{\"places\":[],}", "{\"places\":[,]}",
                "{\"places\":[{\"lat\":NaN,\"lng\":8}]}", "{\"places\":[{\"lat\":1e309,\"lng\":8}]}",
                "{\"places\":[{\"lat\":\"48\",\"lng\":8}]}", "{\"places\":[{\"lat\":48,\"lng\":8,\"lists\":{}}]}",
                "{\"places\":[{\"lat\":48,\"lng\":8,\"photos\":[false]}]}"}) {
            rejectUnchanged(input("claimed.csv", json));
        }
    }

    @Test public void selectedContentIsVerifiedRegardlessOfItsFilename() throws Exception {
        seed();
        rejectUnchanged(input("Saved Places.json", "This is not JSON or CSV"));
        rejectUnchanged(input("Want to go.csv", "<html><body>not KML</body></html>"));
        assertEquals(1, SavedStore.importFile(context, input("actually.csv", GEOJSON)));
        Uri archive = zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE);
        File renamed = new File(context.getCacheDir(), "archive.dat");
        Files.copy(new File(archive.getPath()).toPath(), renamed.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(1, SavedStore.importFiles(context, Collections.singletonList(Uri.fromFile(renamed))).files);
    }

    @Test public void labelsVisibilityAndJsonKmlExportsRoundTrip() throws Exception {
        seed();
        SavedStore.setOnMap(context, SavedStore.STARRED, false);
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertTrue(SavedStore.hiddenOnMap.contains("custom")); assertTrue(SavedStore.hiddenOnMap.contains(SavedStore.STARRED));
        assertEquals("My gym", SavedStore.labelled("gYm").name);
        Uri export = Uri.fromFile(new File(context.getCacheDir(), "export.json"));
        SavedStore.exportJson(context, export);
        assertEquals(0, SavedStore.importFile(context, export));
        assertEquals("My home", SavedStore.labelled("HOME").name);
        Uri kml = Uri.fromFile(new File(context.getCacheDir(), "export.kml"));
        SavedStore.exportKml(context, kml);
        assertEquals(3, SavedStore.importFile(context, kml)); // Home, Work and Gym; unlocated CSV place is omitted.
        assertEquals(1, SavedStore.importFile(context, input("valid.kml", "<k:kml xmlns:k=\"http://www.opengis.net/kml/2.2\">"
                + "<k:Document><k:Folder><k:name>Trips</k:name><k:Placemark><k:name>Pin</k:name><k:Point>"
                + "<k:coordinates>12,52,0</k:coordinates></k:Point></k:Placemark></k:Folder></k:Document></k:kml>")));
        assertTrue(SavedStore.find("52.000000,12.000000").lists.size() > 0);
    }

    @Test public void invalidKmlRollsBackAndLeavesGoodBytes() throws Exception {
        seed();
        for (String kml : new String[]{"<html/>", "<kml><Placemark/></kml>", "<kml><Placemark><Point><coordinates>8,NaN</coordinates></Point></Placemark></kml>",
                "<kml><Placemark><Point><coordinates>8,91</coordinates></Point></Placemark></kml>",
                "<kml><Placemark><Point><coordinates>8,48</coordinates></Point></Placemark><Placemark>",
                "<kml><Placemark><Point><coordinates>8,48</coordinates></Point></Placemark>"}) {
            rejectUnchanged("Malformed KML: " + kml, input("bad.kml", kml));
        }
    }

    @Test public void unreadableSavedContainersAndBytesSurviveCsvAndZipImports() throws Exception {
        File file = new File(context.getFilesDir(), SavedStore.FILE);
        for (byte[] corrupt : new byte[][]{"{\"places\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[]{0, (byte) 0xff, 1, 2}}) {
            Files.write(file.toPath(), corrupt);
            resetLoaded(SavedStore.class); SavedStore.load(context);
            assertThrows(Exception.class, () -> SavedStore.importFile(context, input("Trips.csv", CSV_HEADER + CSV_PLACE)));
            assertThrows(Exception.class, () -> SavedStore.importFile(context, zip("Takeout/Saved/Trips.csv", CSV_HEADER + CSV_PLACE)));
            SavedStore.save(context);
            assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void malformedHistoryContainersArePreservedUntilExplicitClear() throws Exception {
        File file = new File(context.getFilesDir(), HistoryStore.FILE);
        String corrupt = "{\"places\":{}}";
        Files.writeString(file.toPath(), corrupt);
        resetLoaded(HistoryStore.class); HistoryStore.load(context);
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject("{\"name\":\"New\",\"lat\":48,\"lng\":8}"));
        HistoryStore.record(context, p, HistoryStore.VIEWED);
        assertEquals(corrupt, Files.readString(file.toPath()));
        HistoryStore.clear(context);
        assertEquals(0, new JSONObject(Files.readString(file.toPath())).getJSONArray("places").length());
        HistoryStore.record(context, p, HistoryStore.VIEWED);
        resetLoaded(HistoryStore.class); HistoryStore.load(context);
        assertEquals(1, HistoryStore.size());
    }

    @Test public void failedHistoryWriteRestoresBothStoresAndPropagates() throws Exception {
        seed();
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject("{\"ftid\":\"existing\",\"name\":\"Old\",\"lat\":48,\"lng\":8}"));
        HistoryStore.record(context, p, HistoryStore.VIEWED);
        File history = new File(context.getFilesDir(), HistoryStore.FILE);
        byte[] historyDisk = Files.readAllBytes(history.toPath());
        assertTrue(new File(context.getFilesDir(), HistoryStore.FILE + ".tmp").mkdir());
        rejectUnchanged(input("history.json", "{\"places\":[{\"name\":\"New\",\"lat\":49,\"lng\":9}],"
                + "\"history\":[{\"ftid\":\"existing\",\"name\":\"Old\",\"lat\":48,\"lng\":8,\"viewed\":9999999999999}]}"));
        assertArrayEquals(historyDisk, Files.readAllBytes(history.toPath()));
        assertTrue(new File(context.getFilesDir(), HistoryStore.FILE + ".tmp").delete());
    }

    @Test public void failedSavedWriteRollsBackCsvLabelsAndVisibility() throws Exception {
        seed();
        assertTrue(new File(context.getFilesDir(), SavedStore.FILE + ".tmp").mkdir());
        rejectUnchanged(input("Trips.csv", CSV_HEADER + CSV_PLACE), input("metadata.json", "{\"hiddenOnMap\":[\"starred\"],"
                + "\"labels\":[{\"label\":\"New label\",\"place\":{\"name\":\"New\",\"lat\":49,\"lng\":9}}]}"));
        assertTrue(new File(context.getFilesDir(), SavedStore.FILE + ".tmp").delete());
    }

    @Test public void missingCoordinatesAreRejectedWithoutPoisoningFutureSaves() throws Exception {
        assertThrows(Exception.class, () -> importJson("{\"places\":[{\"name\":\"missing coordinates\"}]}"));
        assertEquals(0, SavedStore.places.size());
        assertEquals(1, importJson("{\"places\":[{\"name\":\"valid\",\"lat\":48,\"lng\":8}]}"));
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertEquals(1, SavedStore.places.size());
    }

    @Test public void invalidLaterRecordDoesNotPartiallyImport() throws Exception {
        assertThrows(Exception.class, () -> importJson("{\"places\":[{\"name\":\"first\",\"lat\":48,\"lng\":8},false]}"));
        assertEquals(0, SavedStore.places.size());
    }

    @Test public void outOfRangeCoordinatesAreRejected() throws Exception {
        assertThrows(Exception.class, () -> importJson("{\"places\":[{\"name\":\"bad\",\"lat\":91,\"lng\":8}]}"));
        assertEquals(0, SavedStore.places.size());
    }

    @Test public void corruptSavedFileIsNotOverwrittenByNextSave() throws Exception {
        File file = new File(context.getFilesDir(), SavedStore.FILE);
        String corrupt = "{invalid json"; Files.writeString(file.toPath(), corrupt);
        resetLoaded(SavedStore.class); SavedStore.load(context);
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject("{\"name\":\"new\",\"lat\":48,\"lng\":8}"));
        SavedStore.keep(context, p);
        assertEquals(corrupt, Files.readString(file.toPath()));
    }

    @Test public void corruptHistoryFileIsNotOverwrittenByNextRecord() throws Exception {
        File file = new File(context.getFilesDir(), HistoryStore.FILE);
        String corrupt = "{invalid json"; Files.writeString(file.toPath(), corrupt);
        resetLoaded(HistoryStore.class); HistoryStore.load(context);
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject("{\"name\":\"new\",\"lat\":48,\"lng\":8}"));
        HistoryStore.record(context, p, HistoryStore.VIEWED);
        assertEquals(corrupt, Files.readString(file.toPath()));
    }

    @Test public void failedImportWriteIsReportedAndStateRestored() throws Exception {
        assertTrue(new File(context.getFilesDir(), SavedStore.FILE + ".tmp").mkdir());
        assertThrows(Exception.class, () -> importJson("{\"places\":[{\"name\":\"new\",\"lat\":48,\"lng\":8}]}"));
        assertEquals(0, SavedStore.places.size());
    }

    @Test public void explicitClearCanReplaceAnUnreadableHistoryFile() throws Exception {
        File file = new File(context.getFilesDir(), HistoryStore.FILE);
        Files.writeString(file.toPath(), "{invalid json");
        resetLoaded(HistoryStore.class); HistoryStore.load(context);
        HistoryStore.clear(context);
        assertEquals(0, new JSONObject(Files.readString(file.toPath())).getJSONArray("places").length());
        resetLoaded(HistoryStore.class); HistoryStore.load(context);
        assertEquals(0, HistoryStore.size());
    }

    @Test public void invalidHistoryDoesNotCommitSavedPlaces() throws Exception {
        assertThrows(Exception.class, () -> importJson("{\"places\":[{\"name\":\"new\",\"lat\":48,\"lng\":8}],\"history\":[{\"name\":\"bad\"}]}"));
        assertEquals(0, SavedStore.places.size());
    }

    @Test public void failedHistoryMergeDoesNotMutateExistingEntries() throws Exception {
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject("{\"ftid\":\"existing\",\"name\":\"old\",\"lat\":48,\"lng\":8}"));
        HistoryStore.record(context, p, HistoryStore.VIEWED);
        long original = HistoryStore.find("existing").at[0];
        org.json.JSONArray input = new org.json.JSONArray();
        input.put(new JSONObject().put("ftid", "existing").put("name", "old").put("lat", 48).put("lng", 8).put("viewed", original + 1000));
        input.put(false);
        assertThrows(Exception.class, () -> HistoryStore.merge(context, input));
        assertEquals(original, HistoryStore.find("existing").at[0]);
    }

    @Test public void validImportRoundTripsAndMergesLists() throws Exception {
        assertEquals(1, importJson("{\"places\":[{\"ftid\":\"place\",\"name\":\"cafe\",\"lat\":48,\"lng\":8,\"lists\":[\"want_to_go\"]}]}"));
        assertEquals(0, importJson("{\"places\":[{\"ftid\":\"place\",\"name\":\"cafe\",\"lat\":48,\"lng\":8,\"lists\":[\"starred\"]}]}"));
        resetLoaded(SavedStore.class); SavedStore.load(context);
        assertEquals(2, SavedStore.find("place").lists.size());
    }
}
