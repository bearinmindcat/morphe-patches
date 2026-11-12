package org.ungoogled.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class YouActivityTest extends LibraryTestSupport {
    @Test public void thousandPlaceListRecyclesRowsAndKeepsStableIdentityAfterEnrichment() throws Exception {
        StringBuilder csv = new StringBuilder("Title,Note,URL\n");
        for (int i = 0; i < 1000; i++) csv.append("Point ").append(i).append(",note,\"geo:")
                .append(10 + i * .01).append(",20\"\n");
        SavedStore.importText(context, csv.toString(), "Large.csv");
        String list = listNamed("Large");
        ActivityController<YouActivity> controller = openList(list);
        try {
            YouActivity activity = controller.get();
            ListView view = (ListView) field("libraryView").get(activity);
            layout(activity);
            assertEquals(1000 + view.getHeaderViewsCount() + view.getFooterViewsCount(), view.getAdapter().getCount());
            assertTrue("Only visible rows should be materialized", view.getChildCount() > 0 && view.getChildCount() < 40);
            long rowId = view.getAdapter().getItemId(view.getHeaderViewsCount());
            SavedStore.Place target = SavedStore.inList(list).get(0);
            SavedStore.Place details = new SavedStore.Place();
            details.ftid = "0x123:0x2a"; details.name = "Native title"; details.lat = 1; details.lng = 2;
            SavedStore.applyDetails(target, details);
            SavedStore.save(context);
            Method refresh = YouActivity.class.getDeclaredMethod("requestLibraryRows");
            refresh.setAccessible(true);
            refresh.invoke(activity);
            awaitLibrary();
            assertEquals(rowId, view.getAdapter().getItemId(view.getHeaderViewsCount()));
            view.setSelection(500);
            layout(activity);
            assertTrue("Scrolling must not inflate the whole library", view.getChildCount() < 40);
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void fileExportRequiresDisclosureAndProjectsTheSelectedList() throws Exception {
        SavedStore.importText(context, "Title,Note,URL\nSelected,list note,\"geo:0,0\"\n", "Selected.csv");
        SavedStore.importText(context, "Title,Note,URL\nPRIVATE_SENTINEL,private,\"geo:1,2\"\n", "Other.csv");
        String list = listNamed("Selected");
        SavedStore.inList(list).get(0).note = "PRIVATE_NOTE_SENTINEL";
        ActivityController<YouActivity> controller = openList(list);
        try {
            YouActivity activity = controller.get();
            View share = described(activity.getWindow().getDecorView(), "Share");
            assertNotNull(share);
            share.performClick();
            AlertDialog choices = ShadowAlertDialog.getLatestAlertDialog();
            choices.getListView().performItemClick(null, 0, 0);
            AlertDialog disclosure = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(choices, disclosure);
            String message = ((TextView) disclosure.findViewById(android.R.id.message)).getText().toString();
            assertTrue(message.contains("Home, Work, history"));
            assertTrue(message.contains("private notes"));
            assertTrue(message.contains("Anyone with the file"));
            disclosure.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowActivity.IntentForResult request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(request);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
            assertEquals("application/json", request.intent.getType());
            File file = new File(context.getCacheDir(), "selected.json");
            activity.onActivityResult(request.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            awaitLibrary();
            String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertFalse(contents.contains("SENTINEL"));
            JSONObject snapshot = new JSONObject(contents);
            assertEquals("Selected", snapshot.getJSONObject("list").getString("title"));
            assertEquals(1, snapshot.getJSONArray("places").length());
            assertEquals("list note", snapshot.getJSONArray("places").getJSONObject(0).getString("sharedNote"));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void editingAnImportedListDoesNotTruncateItsCompleteEmoji() throws Exception {
        String emoji = "\uD83D\uDC68\uD83C\uDFFD\u200D\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDC67\uD83C\uDFFD\u200D\uD83D\uDC66\uD83C\uDFFD";
        JSONObject snapshot = new JSONObject().put("format", "trailmark-list").put("version", 1)
                .put("list", new JSONObject().put("type", "list").put("title", "Family")
                        .put("emoji", emoji).put("description", ""))
                .put("places", new org.json.JSONArray());
        String list = ListTransfer.importDocument(snapshot);
        ActivityController<YouActivity> controller = openList(list);
        try {
            SavedPlaces.listDialog(controller.get(), list, (name, icon) -> SavedStore.editList(context, list, name, icon));
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(emoji, ListTransfer.document(list).getJSONObject("list").getString("emoji"));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void multiFileImportReportsEveryReadFileEvenWhenNothingIsNew() throws Exception {
        String list = SavedStore.addList(context, "Current list", "");
        ActivityController<YouActivity> controller = openList(list);
        try {
            android.content.ClipData files = null;
            for (int i = 0; i < 13; i++) {
                File file = new File(context.getCacheDir(), "Part-" + i + ".csv");
                Files.write(file.toPath(), ("Title,Note,URL\nPlace " + i + ",note,\"geo:" + i + ",20\"\n")
                        .getBytes(StandardCharsets.UTF_8));
                if (files == null) files = android.content.ClipData.newRawUri("Saved lists", Uri.fromFile(file));
                else files.addItem(new android.content.ClipData.Item(Uri.fromFile(file)));
            }
            Intent selected = new Intent();
            selected.setClipData(files);
            for (int run = 0; run < 2; run++) {
                controller.get().onActivityResult(3, Activity.RESULT_OK, selected);
                awaitLibrary();
                awaitLibrary();
                AlertDialog result = ShadowAlertDialog.getLatestAlertDialog();
                assertNotNull(result);
                String message = ((TextView) result.findViewById(android.R.id.message)).getText().toString();
                assertTrue(message, message.contains("Read 13 files."));
                assertEquals(13, SavedStore.places.size());
                result.dismiss();
            }
        } finally { controller.pause().stop().destroy(); }
    }

    private ActivityController<YouActivity> openList(String list) throws Exception {
        Intent intent = new Intent(context, YouActivity.class).putExtra(YouActivity.EXTRA_PAGE, "list")
                .putExtra(YouActivity.EXTRA_LIST, list);
        ActivityController<YouActivity> controller = Robolectric.buildActivity(YouActivity.class, intent).create().start().resume().visible();
        awaitLibrary();
        awaitLibrary();
        return controller;
    }

    private static void awaitLibrary() throws Exception {
        ExecutorService executor = (ExecutorService) field("LIBRARY").get(null);
        executor.submit(() -> {}).get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Field field(String name) throws Exception {
        Field field = YouActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void layout(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static View described(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
