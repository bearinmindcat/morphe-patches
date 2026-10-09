package org.ungoogled.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Save without a Google account. Maps' Save buttons end in one of two methods --
 * the list picker (apwd.r) or, behind a flag, quick save (apww.b) -- and both
 * make a signed-out user sign in first. The patch hands the place to save()
 * instead, which saves it on the phone (SavedStore) and opens SaveSheet, a copy
 * of Maps' own "Place saved" sheet. The place sheet's Save button then shows the
 * saved state (buttonState / buttonCount), and YouActivity -- the You tab rebuilt
 * from what the phone keeps -- lists everything.
 */
public final class SavedPlaces {
    /** apdk states: 1 = not saved, 2 Favorites (heart), 3 Want to go, 4 Travel plans (suitcase),
     *  6 Starred (star), 7 another list (check) -- the check also stands for "saved, no list". */
    private static final int STATE_FAVOURITE = 2, STATE_WANT_TO_GO = 3, STATE_TRAVEL = 4, STATE_STARRED = 6, STATE_SAVED = 7;

    private SavedPlaces() {}

    // ---- the Save buttons on screen ------------------------------------------------------

    private static final java.util.List<java.lang.ref.WeakReference<Object>> buttons = new java.util.ArrayList<>();

    /**
     * From each Save button's own bind or state read (the action row's aprm.g, the place sheet
     * header's arqn.a() -- aqem.q() in the older header, arny.l/arny.e in the Compose one -- and a
     * few list rows): remembers the ones alive, so a change made here can redraw them. Maps works
     * a button's saved state out when it binds or draws the place, and would otherwise keep showing
     * the old one until the sheet is rebuilt.
     */
    public static void trackButton(Object button) {
        synchronized (buttons) {
            for (java.util.Iterator<java.lang.ref.WeakReference<Object>> it = buttons.iterator(); it.hasNext(); ) {
                Object o = it.next().get();
                if (o == null) it.remove();
                else if (o == button) return;
            }
            buttons.add(new java.lang.ref.WeakReference<>(button));
        }
    }

    /** Redraws every live Save button: each re-reads its saved state through uaRefresh(), which the patch adds. */
    static void refreshButtons() {
        java.util.List<Object> live = new java.util.ArrayList<>();
        synchronized (buttons) {
            for (java.lang.ref.WeakReference<Object> r : buttons) {
                Object o = r.get();
                if (o != null) live.add(o);
            }
        }
        for (Object o : live) {
            try { o.getClass().getMethod("uaRefresh").invoke(o); } catch (Throwable ignored) {}
        }
    }

    private static java.lang.ref.WeakReference<Activity> resumed = new java.lang.ref.WeakReference<>(null);
    private static boolean tracking;

    /** From Shapes.wrap, at every Activity attach: remember which Maps screen is in front. */
    static void track(Context base) {
        if (tracking) return;
        Context app = base.getApplicationContext();
        if (!(app instanceof android.app.Application)) return;
        tracking = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(new Front());
    }

    static final class Front implements android.app.Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            resumed = new java.lang.ref.WeakReference<>(a);
            // Back in Maps after Add on a list: now its "Add a place" screen can open over it.
            if (pickOpen && !a.getClass().getName().startsWith("org.ungoogled.")) {
                pickOpen = false;
                a.getWindow().getDecorView().postDelayed(() -> {
                    try {
                        showAddPlace(a);
                    } catch (Throwable t) {
                        pickList = null;
                        android.util.Log.w("UA", "Add a place", t);
                    }
                }, 250);
            }
            // Maps is in front: the one moment a timeline left on may be restarted.
            TimelineService.resumeIfWanted(a);
        }
        @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }

    // ---- Add on a list: Maps' own "Add a place" screen ------------------------------------

    /** HistoryStore-style kind for a place picked on "Add a place". */
    static final int PICKED = 64;
    private static final long PICK_WINDOW = 30 * 60 * 1000L;
    private static String pickList, reopenList;
    private static long pickAt;
    private static boolean pickOpen, picked;

    /**
     * Add on one of the user's lists: back to Maps, which opens its own "Add a place" screen (search,
     * or Choose on map); the place picked there goes into [listId] (see interacted, pickedIntoList).
     */
    static void addPlaceTo(Activity from, String listId) {
        pickList = listId;
        pickAt = System.currentTimeMillis();
        pickOpen = true;
        from.startActivity(new Intent().setClassName(from.getPackageName(), "com.google.android.maps.MapsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /**
     * Opens Maps' "Add a place" screen over [maps] (Maps' own activity). The body is the patch's:
     * it builds the screen exactly as a list's Add does in Maps and shows it.
     */
    public static void showAddPlace(Activity maps) {}

    /**
     * From the start of "Add a place"'s pick (after interacted saw the place): true when the place went
     * into the user's list, and the screen should just close; the list's page then opens again.
     */
    public static boolean pickedIntoList() {
        boolean was = picked;
        picked = false;
        if (was) {
            Activity a = resumed.get();
            String list = reopenList;
            if (a != null && list != null) {
                String name = SavedStore.lists.get(list);
                Toast.makeText(a, name != null ? "Added to " + name : "Added", Toast.LENGTH_SHORT).show();
                a.getWindow().getDecorView().postDelayed(() -> a.startActivity(YouActivity.listIntent(a, list)), 300);
            }
        }
        return was;
    }

    // ---- hooks ------------------------------------------------------------------

    /** apwd.r: [activity] is Maps' own Activity, [ftid] its feature id (toString "0x..:0x.."),
     *  [latLng] its position (toString "lat/lng: (lat,lng)"). */
    public static void save(Object activity, String name, Object ftid, Object latLng) {
        try {
            // The quick-save path (apww.b) has no Activity at hand: use the one on screen.
            Activity a = activity instanceof Activity ? (Activity) activity : resumed.get();
            if (a == null) return;
            SavedStore.load(a);
            SavedStore.Place p = place(name, ftid, latLng);
            if (p == null) {
                Toast.makeText(a, "Can't save this place", Toast.LENGTH_SHORT).show();
                return;
            }
            // Its category and photo, from when the place sheet showed it (Viewed).
            HistoryStore.load(a);
            HistoryStore.Entry seen = HistoryStore.find(p.key());
            if (seen != null) p.fillFrom(seen.asPlace());
            SaveSheet.show(a, p, null);
        } catch (Throwable t) {
            android.util.Log.w("UA", "SavedPlaces.save", t);
        }
    }

    /**
     * A place was looked at, routed to, called or shared (HistoryStore's kinds): from the patch's
     * uaInteract on Maps' place class, called by the place sheet's Save chip bind (viewed), the
     * Directions button, and the Call and Share chips. Kept for "Your recent places".
     */
    public static void interacted(String name, Object ftid, Object latLng, String category, Object photos,
            String photoField, float rating, int reviews, int kind) {
        try {
            Context c = Shapes.appContext();
            if (c == null) return;
            SavedStore.Place p = place(name, ftid, latLng);
            if (p == null) return;
            p.category = category != null ? category : "";
            p.photos.addAll(photoUrls(photos, photoField));
            p.rating = rating;
            p.reviews = Math.max(0, reviews);
            if (kind == PICKED) {
                // Picked on "Add a place" for one of the user's lists: into that list.
                String list = pickList;
                pickList = null;
                if (list == null || System.currentTimeMillis() - pickAt > PICK_WINDOW) return;
                SavedStore.load(c);
                if (!SavedStore.lists.containsKey(list)) return;
                HistoryStore.load(c);
                HistoryStore.Entry seen = HistoryStore.find(p.key());
                if (seen != null) p.fillFrom(seen.asPlace());
                SavedStore.Place stored = SavedStore.keep(c, p);
                stored.fillFrom(p);
                Set<String> in = new LinkedHashSet<>(stored.lists);
                in.add(list);
                SavedStore.setLists(c, stored, in);
                reopenList = list;
                picked = true;
                return;
            }
            HistoryStore.record(c, p, kind);
            // A saved place Maps shows: it takes what it lacks (a Takeout list's place has no position or photos).
            SavedStore.refresh(c, p);
        } catch (Throwable ignored) {}
    }

    /**
     * End of the Save button state's constructor, which every place sheet builds: a saved place
     * without a position -- from a Google Takeout list -- takes the one Maps shows.
     */
    public static void placeShown(Object ftid, Object latLng) {
        try {
            if (ftid == null || latLng == null) return;
            Context c = Shapes.appContext();
            if (c == null) return;
            SavedStore.load(c);
            SavedStore.Place at = place(null, ftid, latLng);
            if (at != null && at.ftid != null) SavedStore.locate(c, at.ftid, at.lat, at.lng);
        } catch (Throwable ignored) {}
    }

    /** The first few photos' URLs out of Maps' photo list: each keeps its URL in the field the patch names. */
    private static List<String> photoUrls(Object photos, String field) {
        List<String> out = new ArrayList<>();
        if (!(photos instanceof List) || field == null) return out;
        for (Object o : (List<?>) photos) {
            if (o == null) continue;
            try {
                Object url = o.getClass().getField(field).get(o);
                if (url instanceof String && !((String) url).isEmpty() && !out.contains(url)) out.add((String) url);
            } catch (Throwable ignored) {}
            if (out.size() >= 6) break;
        }
        return out;
    }

    /**
     * End of apdk's constructor: the Save button's state for a place saved here, picked the way
     * apdk picks it for an account's lists (Favorites over Want to go over Travel plans over
     * Starred over any other list); a place in no list gets the "saved" check.
     */
    public static int buttonState(Object ftid, int original) {
        SavedStore.Place p = stored(ftid);
        if (p == null) return original;
        if (p.lists.contains(SavedStore.FAVOURITES)) return STATE_FAVOURITE;
        if (p.lists.contains(SavedStore.WANT_TO_GO)) return STATE_WANT_TO_GO;
        if (p.lists.contains(SavedStore.TRAVEL)) return STATE_TRAVEL;
        if (p.lists.contains(SavedStore.STARRED)) return STATE_STARRED;
        return STATE_SAVED;
    }

    /** How many lists hold it, for "Saved in N lists". */
    public static int buttonCount(Object ftid, int original) {
        SavedStore.Place p = stored(ftid);
        return p == null ? original : p.lists.size();
    }

    private static SavedStore.Place stored(Object ftid) {
        try {
            Context c = Shapes.appContext();
            if (c == null || ftid == null) return null;
            SavedStore.load(c);
            return SavedStore.find(ftid.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    static SavedStore.Place place(String name, Object ftid, Object latLng) {
        if (latLng == null) return null;
        // "lat/lng: (46.95,7.43)"
        String s = latLng.toString();
        int open = s.indexOf('('), comma = s.indexOf(',', open), close = s.indexOf(')', comma);
        if (open < 0 || comma < 0 || close < 0) return null;
        SavedStore.Place p = new SavedStore.Place();
        p.lat = Double.parseDouble(s.substring(open + 1, comma).trim());
        p.lng = Double.parseDouble(s.substring(comma + 1, close).trim());
        String id = ftid == null ? null : ftid.toString();
        p.ftid = id == null || id.isEmpty() || id.equals("0x0:0x0") ? null : id;
        p.name = name != null && !name.isEmpty() ? name : String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng);
        return p;
    }

    interface NameListener { void named(String name); }

    static void newList(Activity a, NameListener then) {
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setHint("List name");
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        new AlertDialog.Builder(a, dialogTheme(a))
                .setTitle("New list")
                .setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) then.named(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Names [p]; [old] is the label being renamed, or null. "Home" and "Work" set those. */
    static void labelDialog(Activity a, SavedStore.Place p, String old, NameListener then) {
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setHint("Label, like Gym");
        if (old != null) field.setText(old);
        float dp = a.getResources().getDisplayMetrics().density;
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), 0);
        box.addView(field);
        String name = p.name != null && !p.name.isEmpty() ? p.name : String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng);
        new AlertDialog.Builder(a, dialogTheme(a))
                .setTitle(old != null ? "Rename label" : "Label " + name)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String label = field.getText().toString().trim();
                    if (label.isEmpty()) return;
                    if (old != null) SavedStore.removeLabel(a, old);
                    if (label.equalsIgnoreCase("home")) SavedStore.setHome(a, SavedStore.aliasOf(p));
                    else if (label.equalsIgnoreCase("work")) SavedStore.setWork(a, SavedStore.aliasOf(p));
                    else SavedStore.setLabel(a, label, p);
                    then.named(label);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static int dialogTheme(Context c) {
        boolean night = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String darkMode = c.getSharedPreferences("settings_preference", Context.MODE_PRIVATE).getString("dark_mode", "FOLLOW_SYSTEM");
        boolean dark = Shapes.BLACK || "ON".equals(darkMode) || (!"OFF".equals(darkMode) && night);
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    // ---- Labels without a Google account: Maps' search box and Add label (issue #36) -----------

    /**
     * From the start of the search box's submit (SearchSuggestFragment.onQueryTextSubmit): a query
     * naming one of the user's labels in Local saved -- Home, Work or their own, ignoring case --
     * opens that place, as Maps does for an account's labels, and is not sent to Google. True when
     * it did; anything else is searched as before (an unset Home or Work then reaches showAliasDialog).
     */
    public static boolean searchLabel(String query) {
        try {
            if (query == null || query.trim().isEmpty()) return false;
            Activity a = resumed.get();
            if (a == null) return false;
            SavedStore.load(a);
            String q = query.trim();
            SavedStore.Place p = SavedStore.labelled(q);
            if (p == null) return false;
            // One Enter can submit twice (the key and the editor action): open the place once.
            long now = android.os.SystemClock.uptimeMillis();
            boolean again = q.equalsIgnoreCase(lastLabel) && now - lastLabelAt < 1500;
            lastLabel = q;
            lastLabelAt = now;
            if (!again) open(a, p);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UA", "label search", t);
            return false;
        }
    }

    private static String lastLabel;
    private static long lastLabelAt;

    /**
     * From the directions waypoint editor's submit (DirectionsWaypointEditorQueryEntered), with the
     * waypoint it is building from the query: signed out, Maps could not route to "Home" at all
     * ("Something went wrong") -- it asks Google to find the words. A query naming one of the user's
     * labels in Local saved -- Home, Work or their own -- becomes that place instead, built the way
     * Maps builds its own "Home" waypoint: the position (and feature id), the label as its name, and
     * no words to look up.
     */
    public static void labelWaypoint(Object waypoint, String query) {
        try {
            if (waypoint == null || query == null || query.trim().isEmpty()) return;
            Context c = Shapes.appContext();
            if (c == null) return;
            SavedStore.load(c);
            String q = query.trim();
            SavedStore.Place p = SavedStore.labelled(q);
            if (p == null || (p.lat == 0 && p.lng == 0)) return;
            // The feature id "0x89b7b7bce3d77213:0xbd541016f552a256": two hex longs.
            int colon = p.ftid == null ? -1 : p.ftid.indexOf(':');
            if (colon > 2 && p.ftid.startsWith("0x") && p.ftid.startsWith("0x", colon + 1)) {
                setWaypointFeature(waypoint, Long.parseUnsignedLong(p.ftid.substring(2, colon), 16),
                        Long.parseUnsignedLong(p.ftid.substring(colon + 3), 16));
            }
            setWaypointPosition(waypoint, p.lat, p.lng);
            setWaypointName(waypoint, q.equalsIgnoreCase("home") ? "Home" : q.equalsIgnoreCase("work") ? "Work" : q);
        } catch (Throwable t) {
            android.util.Log.w("UA", "label waypoint", t);
        }
    }

    /** Rewritten by the patch: gives Maps' waypoint the feature id [high]:[low]. */
    static void setWaypointFeature(Object waypoint, long high, long low) {}

    /** Rewritten by the patch: gives Maps' waypoint the position [lat], [lng]. */
    static void setWaypointPosition(Object waypoint, double lat, double lng) {}

    /** Rewritten by the patch: names Maps' waypoint [name] and takes its query away. */
    static void setWaypointName(Object waypoint, String name) {}

    private static Object aliasAnswer;

    /** Maps' search answered "Home" or "Work" with its personal-place block: kept for showAliasDialog. */
    public static void aliasAnswer(Object block) {
        aliasAnswer = block;
    }

    /** Rewritten by the patch: the kind of personal place in that block, 0 Home and 1 Work (throws when it has none). */
    static int aliasKind(Object block) { return -1; }

    /** Rewritten by the patch: the name of the method that shows Maps' dialogs over an Activity. */
    static String showMethod() { return ""; }

    /**
     * In place of showing Maps' "Search for Home?" dialog. Maps keeps Home and Work in the Google
     * account, so signed out, searching "Home" or "Work" only ever asked to sign in, even with Home
     * set in Local saved. The local Home or Work opens instead, and when that one is not set, Local
     * saved's Labeled page, where it is set. Any other personal place keeps Maps' dialog.
     */
    public static void showAliasDialog(Object dialog, Object activity) {
        Object block = aliasAnswer;
        aliasAnswer = null;
        try {
            int kind = -1;
            try {
                kind = aliasKind(block);
            } catch (Throwable noPlace) {}
            if ((kind == 0 || kind == 1) && activity instanceof Activity) {
                Activity a = (Activity) activity;
                SavedStore.load(a);
                SavedStore.Place place = kind == 0 ? SavedStore.home : SavedStore.work;
                if (place != null) {
                    open(a, place);
                } else {
                    Toast.makeText(a, (kind == 0 ? "Home" : "Work") + " is not set: choose Set as " + (kind == 0 ? "Home" : "Work")
                            + " in a place's menu", Toast.LENGTH_LONG).show();
                    a.startActivity(YouActivity.labeledIntent(a));
                }
                return;
            }
        } catch (Throwable t) {
            android.util.Log.w("UA", "personal place search", t);
        }
        try {
            for (java.lang.reflect.Method m : dialog.getClass().getMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (m.getName().equals(showMethod()) && params.length == 1 && params[0].isInstance(activity)) {
                    m.invoke(dialog, activity);
                    return;
                }
            }
            throw new NoSuchMethodException(showMethod());
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw SavedPlaces.<RuntimeException>sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw SavedPlaces.<RuntimeException>sneaky(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * From the uaLabel() the patch gives Maps' "Add label" prompt (the place sheet's More, and the
     * older overflow menu): Maps keeps labels in the Google account, so signed out it only asked to
     * sign in. The place is labelled here instead, in Local saved -- as Home or Work too. [activity]
     * is the prompt's own; true when it is handled, and Maps' prompt is not shown.
     */
    public static boolean label(Object activity, String name, Object ftid, Object latLng) {
        try {
            Activity a = activity instanceof Activity ? (Activity) activity : resumed.get();
            if (a == null) return false;
            SavedStore.load(a);
            SavedStore.Place p = place(name, ftid, latLng);
            if (p == null) return false;
            HistoryStore.load(a);
            HistoryStore.Entry seen = HistoryStore.find(p.key());
            if (seen != null) p.fillFrom(seen.asPlace());
            List<String> labels = SavedStore.labelsFor(p);
            labelDialog(a, p, labels.isEmpty() ? null : labels.get(0), label -> Toast.makeText(a,
                    (label.equalsIgnoreCase("home") ? "Set as Home" : label.equalsIgnoreCase("work") ? "Set as Work"
                            : "Labeled " + label) + " in Local saved", Toast.LENGTH_SHORT).show());
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UA", "Add label", t);
            return false;
        }
    }

    // ---- opening saved places ---------------------------------------------------------

    /** Opens the place's own sheet (by Maps' customer id, the feature id's second half) or, for a bare spot, a pin. */
    static void open(Context c, SavedStore.Place p) {
        start(c, Uri.parse(placeUrl(p)));
    }

    static String placeUrl(SavedStore.Place p) {
        String cid = cid(p.ftid);
        if (cid != null) return "https://maps.google.com/?cid=" + cid;
        return String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", p.lat, p.lng, p.lat, p.lng, Uri.encode(p.name));
    }

    /** Directions from here to [p], in Maps' own directions screen. */
    static void directions(Context c, SavedStore.Place p) {
        // A Takeout list's place Maps has not shown yet: its own sheet, whose Directions knows where it is.
        if (!p.located()) {
            open(c, p);
            return;
        }
        start(c, Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%.7f,%.7f", p.lat, p.lng)));
    }

    /** "0x47...:0x1a2b" -> the second half as an unsigned decimal. */
    static String cid(String ftid) {
        if (ftid == null) return null;
        int colon = ftid.indexOf(':');
        if (colon < 0 || !ftid.startsWith("0x", colon + 1)) return null;
        try {
            return Long.toUnsignedString(Long.parseUnsignedLong(ftid.substring(colon + 3), 16));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void start(Context c, Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(c.getPackageName());
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(c, "Can't open this place", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The account sheet's "Local saved" row: opens that screen. Unlike Customization it lets the
     * sheet close, or a place opened from the list would come up underneath it.
     */
    public static final class OpenScreen implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            // The sheet's dispatcher calls row listeners with a null View.
            Context c = v != null ? v.getContext() : resumed.get();
            if (c == null) c = Shapes.appContext();
            if (c == null) return;
            Intent i = Screens.intent(c, YouActivity.class);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }
}
