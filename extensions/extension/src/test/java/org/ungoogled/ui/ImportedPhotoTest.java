package org.ungoogled.ui;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ImportedPhotoTest {
    private Context context;

    @Before public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        for (String file : new String[]{SavedStore.FILE, HistoryStore.FILE}) Files.deleteIfExists(new File(context.getFilesDir(), file).toPath());
        for (Class<?> type : new Class<?>[]{SavedStore.class, HistoryStore.class}) {
            Field f = type.getDeclaredField("loaded"); f.setAccessible(true); f.setBoolean(null, false);
        }
        SavedStore.load(context); HistoryStore.load(context);
    }

    private void importJson(JSONObject json) throws Exception {
        File file = new File(context.getCacheDir(), "photo-import.json");
        Files.writeString(file.toPath(), json.toString());
        SavedStore.importFile(context, Uri.fromFile(file));
    }

    private JSONObject place(String id) throws Exception {
        return new JSONObject().put("ftid", id).put("name", id).put("lat", 48).put("lng", 8)
                .put("photos", new org.json.JSONArray().put("https://example.invalid/photo"));
    }

    @Test public void importedPhotoCannotTriggerThumbnailHttpRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/photo", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 1);
            exchange.getResponseBody().write(0);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/photo";
            JSONObject p = place("imported").put("photos", new org.json.JSONArray().put(url));
            importJson(new JSONObject().put("places", new org.json.JSONArray().put(p)));
            String sized = Thumbs.sized(SavedStore.find("imported").photo(), 96);
            if (sized != null) {
                Method fetch = Thumbs.class.getDeclaredMethod("fetch", Context.class, String.class, int.class);
                fetch.setAccessible(true); fetch.invoke(null, context, sized, 96);
            }
            assertEquals("Opening imported places must not contact an address supplied by the document", 0, requests.get());
        } finally { server.stop(0); }
    }

    @Test public void allImportedPhotoLocationsAreRemoved() throws Exception {
        JSONObject oldPhoto = place("legacy"); oldPhoto.remove("photos"); oldPhoto.put("photo", "https://example.invalid/legacy");
        JSONObject root = new JSONObject()
                .put("places", new org.json.JSONArray().put(oldPhoto))
                .put("home", place("home")).put("work", place("work"))
                .put("labels", new org.json.JSONArray().put(new JSONObject().put("label", "label").put("place", place("label"))))
                .put("history", new org.json.JSONArray().put(place("history").put("viewed", 1)));
        importJson(root);
        assertEquals("", SavedStore.find("legacy").photo());
        assertEquals("", SavedStore.home.photo()); assertEquals("", SavedStore.work.photo());
        assertEquals("", SavedStore.labels.get("label").photo());
        assertTrue(HistoryStore.find("history").photos.isEmpty());
        importJson(new JSONObject().put("history", new org.json.JSONArray().put(place("history-only").put("viewed", 1))));
        assertTrue(HistoryStore.find("history-only").photos.isEmpty());
    }

    @Test public void normalLocallySavedPhotoSurvivesReloadAndMerge() throws Exception {
        SavedStore.Place local = SavedStore.Place.fromJson(place("local"));
        SavedStore.keep(context, local);
        importJson(new JSONObject().put("places", new org.json.JSONArray().put(place("local"))));
        Field loaded = SavedStore.class.getDeclaredField("loaded"); loaded.setAccessible(true); loaded.setBoolean(null, false);
        SavedStore.load(context);
        assertEquals("https://example.invalid/photo", SavedStore.find("local").photo());
    }
}
