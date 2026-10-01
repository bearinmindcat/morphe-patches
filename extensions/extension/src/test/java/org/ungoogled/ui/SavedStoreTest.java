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
        File input = new File(context.getCacheDir(), "places-test.json");
        Files.writeString(input.toPath(), text);
        return SavedStore.importFile(context, Uri.fromFile(input));
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
