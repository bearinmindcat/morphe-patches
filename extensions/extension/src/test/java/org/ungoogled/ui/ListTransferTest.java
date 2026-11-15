package org.ungoogled.ui;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class ListTransferTest extends LibraryTestSupport {
    @Test public void projectsOnlySelectedListAndExplicitNotesNeverPrivateDataOrNativeCache() throws Exception {
        String chosen = SavedStore.addList(context, "Shared", "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08");
        String other = SavedStore.addList(context, "PRIVATE_LIST_SENTINEL", "");
        SavedStore.Place p = SavedStore.Place.fromJson(new JSONObject().put("name", "Museum")
                .put("ftid", "0x1:0x2").put("hasCoordinates", true).put("lat", 48.8).put("lng", 2.2)
                .put("opaquePrivateData", "CREDENTIAL_SENTINEL"));
        p.note = "PRIVATE_NOTE_SENTINEL";
        p.hours = "VENDOR_HOURS_SENTINEL";
        p.photos.add("https://lh3.googleusercontent.com/VENDOR_PHOTO_SENTINEL");
        p.category = "VENDOR_CATEGORY_SENTINEL"; p.rating = 4.7f; p.reviews = 2345;
        p.lists.add(chosen); p.lists.add(other);
        p.listNotes.put(chosen, "Explicit list annotation");
        p.listNotes.put(other, "PRIVATE_OTHER_NOTE_SENTINEL");
        p.sources.add(new JSONObject().put("original", "PRIVATE_SOURCE_ROW_SENTINEL"));
        SavedStore.places.put(p.key(), p);
        SavedStore.home = SavedStore.fromMapsLink("geo:1,2", "HOME_SENTINEL");
        SavedStore.work = SavedStore.fromMapsLink("geo:3,4", "WORK_SENTINEL");
        SavedStore.labels.put("LABEL_SENTINEL", SavedStore.home);
        HistoryStore.record(context, SavedStore.fromMapsLink("geo:5,6", "HISTORY_SENTINEL"), HistoryStore.VIEWED);
        JSONObject document = ListTransfer.document(chosen);
        String serialized = document.toString();
        assertFalse(serialized, serialized.contains("SENTINEL"));
        assertFalse(serialized.contains(other));
        JSONObject place = document.getJSONArray("places").getJSONObject(0);
        assertEquals("Explicit list annotation", place.getString("sharedNote"));
        assertEquals(p.membershipId(chosen), place.getString("id"));
        assertTrue(place.isNull("lat"));
        assertTrue(place.isNull("lng"));
        assertEquals("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08", document.getJSONObject("list").getString("emoji"));
    }

    @Test public void snapshotsRoundTripEmojiDuplicatesNotesAndSourceLinksAfterReloadAndEnrichment() throws Exception {
        String privateList = SavedStore.addList(context, "Saturday plan", "");
        SavedStore.Place existing = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Private title");
        existing.note = "PRIVATE_SENTINEL";
        existing.lists.add(privateList);
        SavedStore.keep(context, existing);
        String[] emojis = {"\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66",
                "\u2764\uFE0F", "\uD83D\uDC4D\uD83C\uDFFD"};
        for (String emoji : emojis) {
            JSONObject original = snapshot(emoji);
            String list = ListTransfer.importDocument(original);
            assertNotEquals(privateList, list);
            assertEquals(3, SavedStore.countIn(list));
            SavedStore.Place duplicate = SavedStore.inList(list).get(1);
            assertTrue(duplicate.separateEntry);
            assertEquals("", duplicate.note);
            SavedStore.Place details = new SavedStore.Place();
            details.ftid = "0x123:0x2a"; details.name = "Native title"; details.lat = 1; details.lng = 2;
            details.photos.add("https://lh3.googleusercontent.com/NATIVE_SENTINEL");
            SavedStore.applyDetails(duplicate, details);
            SavedStore.save(context);
            reload();
            assertEquals(3, SavedStore.countIn(list));
            assertJsonEquals(original, ListTransfer.document(list));
            assertEquals("PRIVATE_SENTINEL", SavedStore.find(existing.id).note);
        }
    }

    @Test public void importingThroughNormalFilePathCreatesIndependentLocalCopy() throws Exception {
        SavedStore.importText(context, snapshot("\u2615").toString(), "list.json");
        String list = listNamed("Saturday plan");
        assertEquals(3, SavedStore.countIn(list));
        assertEquals(3, SavedStore.places.size());
        SavedStore.importText(context, snapshot("\u2615").toString(), "list.json");
        assertEquals(6, SavedStore.places.size());
        assertEquals(2, SavedStore.listNames().values().stream().filter("Saturday plan"::equals).count());
    }

    @Test public void invalidSnapshotsCannotPartiallyImportOrExportExtraFields() throws Exception {
        JSONObject valid = snapshot("");
        int lists = SavedStore.lists.size(), places = SavedStore.places.size();
        for (JSONObject invalid : new JSONObject[]{
                new JSONObject(valid.toString()).put("version", 2),
                new JSONObject(valid.toString()).put("version", 1.5),
                new JSONObject(valid.toString()).put("version", "1"),
                new JSONObject(valid.toString()).put("unexpected", true),
                new JSONObject(valid.toString()).put("places", new JSONArray().put(valid.getJSONArray("places").getJSONObject(0)).put(false)),
                new JSONObject(valid.toString()).put("places", new JSONArray().put(valid.getJSONArray("places").getJSONObject(0))
                        .put(valid.getJSONArray("places").getJSONObject(0)))}) {
            assertThrows(Exception.class, () -> SavedStore.importText(context, invalid.toString(), "bad.json"));
            assertEquals(lists, SavedStore.lists.size());
            assertEquals(places, SavedStore.places.size());
        }
        String list = ListTransfer.importDocument(valid);
        SavedStore.Place p = SavedStore.inList(list).get(0);
        p.listEntries.get(list).put("photos", new JSONArray().put("PRIVATE_SENTINEL"));
        assertThrows(IllegalArgumentException.class, () -> ListTransfer.document(list));
    }

    @Test public void credentialsInSourceLinksAreRejectedRatherThanShared() throws Exception {
        String list = SavedStore.addList(context, "Private source", "");
        for (String link : new String[]{"https://user:password@example.com/place", "https://example.com/place?access_token=secret",
                "https://maps.googleapis.com/place?key=secret"}) {
            SavedStore.places.clear();
            SavedStore.Place p = SavedStore.fromMapsLink(link, "Retained");
            p.lists.add(list);
            SavedStore.places.put(p.key(), p);
            assertThrows(IllegalArgumentException.class, () -> ListTransfer.document(list));
            assertEquals(link, p.sourceUrl);
        }
    }

    @Test public void sourceCoordinateZeroIsPortableButNativeCoordinatesAreNotCopied() throws Exception {
        String list = SavedStore.addList(context, "Points", "");
        SavedStore.Place source = SavedStore.fromMapsLink("geo:0,0", "Own point");
        source.lists.add(list);
        SavedStore.places.put(source.key(), source);
        JSONObject entry = ListTransfer.document(list).getJSONArray("places").getJSONObject(0);
        assertEquals(0, entry.getDouble("lat"), 0);
        assertEquals(0, entry.getDouble("lng"), 0);
        assertTrue(entry.getString("sourceUrl").startsWith("https://www.google.com/maps/search/"));
        assertEquals("geo:0,0", source.sourceUrl);
    }

    private static JSONObject snapshot(String emoji) throws Exception {
        JSONArray places = new JSONArray();
        for (int i = 0; i < 3; i++) {
            places.put(new JSONObject().put("type", "place").put("title", i == 2 ? "No coordinates" : "Museum " + i)
                    .put("sourceUrl", i == 2 ? "" : "https://maps.google.com/?cid=42")
                    .put("lat", JSONObject.NULL).put("lng", JSONObject.NULL).put("sharedNote", "Plan " + i).put("id", "entry-" + i));
        }
        return new JSONObject().put("format", "trailmark-list").put("version", 1)
                .put("list", new JSONObject().put("type", "list").put("title", "Saturday plan").put("emoji", emoji).put("description", "An offline copy"))
                .put("places", places);
    }

    private static void assertJsonEquals(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject a = (JSONObject) expected, b = (JSONObject) actual;
            assertEquals(a.length(), b.length());
            for (java.util.Iterator<String> it = a.keys(); it.hasNext();) {
                String key = it.next();
                assertJsonEquals(a.get(key), b.get(key));
            }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray a = (JSONArray) expected, b = (JSONArray) actual;
            assertEquals(a.length(), b.length());
            for (int i = 0; i < a.length(); i++) assertJsonEquals(a.get(i), b.get(i));
        } else assertEquals(expected, actual);
    }
}
