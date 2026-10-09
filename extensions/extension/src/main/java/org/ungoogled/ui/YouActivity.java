package org.ungoogled.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Local saved", from the account sheet: Maps' You tab rebuilt from what this phone keeps, since signed-out
 * Maps has none of it (its You tab is a front end for the Google account). Laid out as Maps' own,
 * measured off it: Your recent places -- the places looked at, routed to, called, shared or saved,
 * each with its photo (or a pin), category and last use, a Save button and a menu, then "See all" --
 * Your saves (all saved places, the lists, Labeled), and Visited (the Timeline). One page per launch:
 * the You page, Your places (See all, with Maps' filters and search), one list, all saved places,
 * Labeled.
 */
public final class YouActivity extends UiScreen {
    static final String EXTRA_PAGE = "page", EXTRA_LIST = "list";
    private static final String EXTRA_KIND = "kind", EXTRA_SAVED_LIST = "savedList", EXTRA_CATEGORY = "category",
            EXTRA_SEARCH = "search";
    private static final String YOU = "you", PLACES = "places", LIST = "list", SAVED = "saved", LABELED = "labeled";
    private static final int EXPORT_JSON = 1, EXPORT_KML = 2, IMPORT = 3, EXPORT_RECENT = 4;
    /** Beside HistoryStore's kinds: saved, and any use at all ("All Maps history"). */
    private static final int KIND_SAVED = 16, KIND_HISTORY = 32;
    private static final int[] HISTORY_KINDS = {KIND_HISTORY, HistoryStore.VIEWED, HistoryStore.DIRECTIONS,
            HistoryStore.CALLED, HistoryStore.SHARED};
    private static final String[] HISTORY_NAMES = {"All Maps history", "Viewed", "Got directions", "Called", "Shared"};
    /** How many lists (Labeled included) Your saves shows before "More", as Maps does. */
    private static final int LISTS_SHOWN = 4;

    /** Maps' own icons, by id: the merged APK strips their names. */
    private static final int BOOKMARK = 0x7f080519, BOOKMARK_FILL = 0x7f080515, HOME = 0x7f080587, WORK = 0x7f08063a,
            MORE = 0x7f0805c4, ADD = 0x7f0804ec, SHARE = 0x7f0805fe, EDIT = 0x7f080557, ADD_PLACE = 0x7f0804e6,
            GS_HISTORY = 0x7f080584, GS_DELETE = 0x7f080542;
    /** Maps' list sort order: Recently edited (newest first), Distance, Editor's order (as added). */
    private static final String[] SORTS = {"Recently edited", "Distance", "Editor's order"};
    private static final String STAR = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z";

    private String page = YOU, listId;
    // Your places' filters: a kind (0 = everything), a saved list, a category, a search.
    private int kind;
    private String savedList, category, query = "";
    private boolean allLists;
    private int sort;
    private FrameLayout filterBar;
    private EditText search;

    // Maps' You tab colours, light as sampled off it; dark and Black their counterparts.
    int t1() { return dark ? 0xFFE3E3E3 : 0xFF535353; }
    int t2() { return dark ? 0xFFC4C7C5 : 0xFF5E5E5E; }
    int t3() { return dark ? 0xFFA8ABAF : 0xFF717579; }
    int ink() { return dark ? 0xFFE3E3E3 : 0xFF1F1F1F; }
    int fill() { return black ? 0xFF1B1B1B : dark ? 0xFF282A2C : 0xFFF2F2F2; }
    int band() { return black ? 0xFF121212 : dark ? 0xFF1E1F20 : 0xFFF1F3F4; }
    int card() { return black ? 0xFF141A1B : dark ? 0xFF1E2426 : 0xFFF0F5F6; }

    @Override int titleColor() { return t1(); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = getIntent();
        if (i != null) {
            if (i.getStringExtra(EXTRA_PAGE) != null) page = i.getStringExtra(EXTRA_PAGE);
            listId = i.getStringExtra(EXTRA_LIST);
            kind = i.getIntExtra(EXTRA_KIND, 0);
            savedList = i.getStringExtra(EXTRA_SAVED_LIST);
            category = i.getStringExtra(EXTRA_CATEGORY);
        }
        SavedStore.load(this);
        HistoryStore.load(this);
        // A list's page draws its own header, as Maps' does; the others have the usual title bar.
        frame(pageTitle(), !(LIST.equals(page) || SAVED.equals(page)));
        if (search != null && i != null && i.getBooleanExtra(EXTRA_SEARCH, false)) {
            search.requestFocus();
            search.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.showSoftInput(search, 0);
            }, 200);
        }
    }

    private String pageTitle() {
        switch (page) {
            case PLACES: return "Your places";
            case SAVED: return "All saved places";
            case LABELED: return "Labeled";
            case LIST: {
                String name = SavedStore.lists.get(listId);
                return name != null ? name : "List";
            }
            default: return "Local saved";
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (LIST.equals(page) && !SavedStore.lists.containsKey(listId)) { finish(); return; }
        render();
    }

    /** Back to Maps: a place saved or unsaved here must not keep its old Save button. */
    @Override protected void onPause() {
        super.onPause();
        SavedPlaces.refreshButtons();
    }

    /** One of the user's lists' page (after a place is added to it from Maps' "Add a place"). */
    static Intent listIntent(android.content.Context c, String list) {
        return Screens.intent(c, YouActivity.class).putExtra(EXTRA_PAGE, LIST).putExtra(EXTRA_LIST, list);
    }

    private void go(String to, String list) {
        startActivity(Screens.intent(this, YouActivity.class).putExtra(EXTRA_PAGE, to).putExtra(EXTRA_LIST, list));
    }

    /** Your places, filtered: what the You page's search and filter chips open. */
    private void places(int withKind, String withList, String withCategory, boolean withSearch) {
        startActivity(Screens.intent(this, YouActivity.class).putExtra(EXTRA_PAGE, PLACES).putExtra(EXTRA_KIND, withKind)
                .putExtra(EXTRA_SAVED_LIST, withList).putExtra(EXTRA_CATEGORY, withCategory).putExtra(EXTRA_SEARCH, withSearch));
    }

    private void render() {
        body.removeAllViews();
        switch (page) {
            case PLACES: renderPlaces(); break;
            case LIST: renderList(); break;
            case SAVED: renderSaved(); break;
            case LABELED: renderLabeled(); break;
            default: renderYou();
        }
    }

    // ---- the You page ------------------------------------------------------------------

    private void renderYou() {
        renderAccount();
        if (!Shapes.microgPatched()) {
            body.addView(bandView());
            renderRecent();
        }

        body.addView(bandView());
        body.addView(sectionTitle("Your saves", null, "New list", v ->
                SavedPlaces.newList(this, n -> { SavedStore.addList(this, n); render(); })));
        body.addView(allSavedCard());
        List<View> lists = new ArrayList<>();
        for (Map.Entry<String, String> e : listsByUse()) lists.add(listRow(e.getKey(), e.getValue()));
        lists.add(Math.min(LISTS_SHOWN - 1, lists.size()), labeledRow());
        for (int i = 0; i < lists.size(); i++) if (allLists || i < LISTS_SHOWN) body.addView(lists.get(i));
        if (lists.size() > LISTS_SHOWN) body.addView(moreToggle());

        if (Shapes.timelinePatched()) {
            body.addView(bandView());
            body.addView(sectionTitle("Visited", "From your Timeline", null, null));
            TextView explore = label("Explore Timeline", 14, accent(), textMedium);
            explore.setGravity(Gravity.CENTER);
            explore.setPadding(dp(12), dp(14), dp(12), dp(14));
            explore.setOnClickListener(v -> startActivity(Screens.intent(this, TimelineActivity.class)));
            body.addView(explore, new LinearLayout.LayoutParams(-1, -2));
            body.addView(shortcut(new PathIcon(PathIcon.TIMELINE, onTonal()), "Timeline",
                    v -> startActivity(Screens.intent(this, TimelineActivity.class))));
        }

        body.addView(bandView());
        body.addView(sectionTitle("Backup", null, null, null));
        body.addView(listLikeRow(new PathIcon(PathIcon.DOWNLOAD, accent()), "Export",
                null /* "A backup file, or KML for other map apps" */, v -> exportMenu(), null));
        body.addView(listLikeRow(new PathIcon(PathIcon.UPLOAD, accent()), "Import",
                null /* "A backup, KML, or Google Takeout's Saved Places.json" */, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), IMPORT), null));
    }

    /**
     * A copy of the Google account's lists to keep on the phone. microG Maps saves to the account,
     * as Maps does, and shows this in place of the recent places it keeps no history of; Ungoogled
     * Maps shows it too, so the two pages match, though it is never signed in to an account.
     */
    private void renderAccount() {
        body.addView(sectionTitle("Your Google account", Shapes.microgPatched()
                ? "Saving stays with your account. Pull copies its saved lists here, to keep on this phone."
                : "Pull copies the saved lists of a Google account signed in to Maps. Ungoogled Maps saves on this phone only.",
                null, null));
        long at = getSharedPreferences(Shapes.PREFS, MODE_PRIVATE).getLong(AccountSaves.KEY_PULLED_AT, 0);
        body.addView(listLikeRow(new PathIcon(PathIcon.DOWNLOAD, accent()), "Pull from Google account",
                at == 0 ? null : System.currentTimeMillis() - at < DateUtils.MINUTE_IN_MILLIS ? "Last pulled just now"
                        : "Last pulled " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                v -> pullFromAccount(), null));
    }

    private boolean pulling;

    /** Reads the account's lists off the phone (no network) and adds what is new here. */
    private void pullFromAccount() {
        if (pulling) return;
        pulling = true;
        new Thread(() -> {
            String done = null, problem = null;
            try {
                AccountSaves.Result r = AccountSaves.pull(this);
                if (r.places == 0) {
                    problem = Shapes.microgPatched()
                            ? "Your account's saved lists aren't on this phone yet. Sign in, open Saved in Maps once, then pull again."
                            : "Ungoogled Maps isn't signed in to a Google account, so there are no account lists to pull. "
                                    + "To bring your lists over, use Import with Google Takeout's Saved Places.json.";
                } else {
                    done = "Pulled " + count(r.places) + (r.lists == 0 ? "" : " from " + (r.lists == 1 ? "1 list" : r.lists + " lists"))
                            + (r.added == 0 ? ", nothing new" : r.added == 1 ? ", 1 new" : ", " + r.added + " new");
                }
            } catch (Throwable t) {
                android.util.Log.w("UA", "Pull from Google account", t);
                problem = "Couldn't read your account's saved lists: " + t.getMessage();
            }
            String ok = done, failed = problem;
            runOnUiThread(() -> {
                pulling = false;
                if (isFinishing() || isDestroyed()) return;
                if (ok != null) Toast.makeText(this, ok, Toast.LENGTH_LONG).show();
                else new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                        .setTitle("Nothing to pull").setMessage(failed).setPositiveButton("OK", null).show();
                render();
            });
        }, "UA-pull").start();
    }

    private void renderRecent() {
        body.addView(sectionTitle("Your recent places", "From your Maps history and saves", null, null));
        body.addView(filterRow(true));
        List<Item> recent = items(0, null, null, "");
        if (recent.isEmpty()) {
            body.addView(hint(HistoryStore.enabled(this)
                    ? "Places you look at, get directions to, call, share or save show up here. They stay on this phone."
                    : "Remembering the places you look at is off. Turn it on below."));
        }
        for (int i = 0; i < Math.min(3, recent.size()); i++) body.addView(placeRow(recent.get(i)));
        body.addView(pill("See all", v -> places(0, null, null, false)));
        // What fills this section, and forgetting it: Maps keeps these in the Google account's
        // settings; here they sit with the places they are about.
        body.addView(switchRow(drawable(GS_HISTORY, accent(), new PathIcon(PathIcon.TIMELINE, accent())),
                "Remember places I look at", HistoryStore.enabled(this), on -> { HistoryStore.setEnabled(this, on); render(); }));
        body.addView(listLikeRow(drawable(GS_DELETE, accent(), new PathIcon(PathIcon.ADD, accent())),
                "Clear recent places", null, v -> confirmClear(), null));
    }

    /** The lists, most recently used first (by their newest place), empty ones last. */
    private List<Map.Entry<String, String>> listsByUse() {
        List<Map.Entry<String, String>> out = new ArrayList<>(SavedStore.lists.entrySet());
        Map<String, Long> newest = new LinkedHashMap<>();
        for (SavedStore.Place p : SavedStore.allSaved()) for (String id : p.lists) newest.merge(id, p.added, Math::max);
        out.sort((a, b) -> Long.compare(newest.getOrDefault(b.getKey(), 0L), newest.getOrDefault(a.getKey(), 0L)));
        return out;
    }

    /** A section's title (18 sp) over its summary, with an optional "+ link" on the right, as in Maps' You tab. */
    private View sectionTitle(String title, String sub, String link, View.OnClickListener onLink) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(20), dp(8), dp(8));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(title, 18, t1(), sansMedium));
        if (sub != null) {
            TextView s = label(sub, 14, t2(), text);
            s.setPadding(0, dp(6), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (link != null) {
            TextView l = label(link, 14, accent(), textMedium);
            Drawable plus = new PathIcon(PathIcon.ADD, accent());
            plus.setBounds(0, 0, dp(20), dp(20));
            l.setCompoundDrawables(plus, null, null, null);
            l.setCompoundDrawablePadding(dp(8));
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(12), dp(12), dp(12), dp(12));
            l.setOnClickListener(onLink);
            row.addView(l);
        }
        return row;
    }

    private View bandView() {
        View v = new View(this);
        v.setBackgroundColor(band());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8));
        lp.topMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    /** The grey pill ("See all"). */
    private View pill(String s, View.OnClickListener click) {
        TextView b = label(s, 14, t1(), textMedium);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill());
        g.setCornerRadius(dp(20));
        b.setBackground(g);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(dp(20), dp(8), dp(20), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** The search button and the Saved / Category / Maps history dropdowns, as over Maps' recent places. */
    private View filterRow(boolean onYouPage) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(16), dp(8));
        View first;
        String savedText = kind == KIND_SAVED ? (savedList != null ? nameOfList(savedList) : "All saved") : "Saved";
        if (onYouPage) {
            // Maps' magnifier: a 24 dp icon (its glyph 27 dp in) in a 48 dp button, the chips right after it.
            ImageView s = iconButton(new PathIcon(PathIcon.SEARCH, ink()), "Search your places");
            s.setScaleType(ImageView.ScaleType.CENTER);
            s.setOnClickListener(v -> places(0, null, null, true));
            row.addView(s, new LinearLayout.LayoutParams(dp(48), dp(48)));
            first = dropChip(savedText, kind == KIND_SAVED, this::savedMenu);
            ((LinearLayout.LayoutParams) first.getLayoutParams()).leftMargin = 0;
        } else {
            row.setPadding(dp(14), dp(4), dp(16), dp(8));
            first = dropChip(savedText, kind == KIND_SAVED, this::savedMenu);
        }
        row.addView(first);
        row.addView(dropChip(category != null ? category : "Category", category != null, this::categoryMenu));
        int h = historyIndex(kind);
        row.addView(dropChip(h >= 0 ? HISTORY_NAMES[h] : "Maps history", h >= 0, this::historyMenu));
        if (!onYouPage && (kind != 0 || category != null)) {
            row.addView(dropChip("Clear", false, v -> { kind = 0; savedList = null; category = null; refilter(); }));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    /** A filled filter chip with a drop-down arrow; [on] tints it as applied. */
    private View dropChip(String s, boolean on, View.OnClickListener click) {
        TextView c = label(s, 14, on ? onTonal() : t1(), text);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(12), 0, dp(8), 0);
        if (!"Clear".equals(s)) {
            Drawable arrow = new PathIcon(PathIcon.ARROW_DROP_DOWN, on ? onTonal() : t1());
            arrow.setBounds(0, 0, dp(18), dp(18));
            c.setCompoundDrawables(null, null, arrow, null);
            c.setCompoundDrawablePadding(dp(4));
        } else {
            c.setPadding(dp(12), 0, dp(12), 0);
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? tonal() : fill());
        g.setCornerRadius(dp(8));
        c.setBackground(g);
        c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.leftMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private static int historyIndex(int k) {
        for (int i = 0; i < HISTORY_KINDS.length; i++) if (HISTORY_KINDS[i] == k) return i;
        return -1;
    }

    private void savedMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        List<String> ids = new ArrayList<>();
        m.getMenu().add(0, 0, 0, "All saved");
        ids.add(null);
        for (Map.Entry<String, String> e : SavedStore.lists.entrySet()) {
            m.getMenu().add(0, ids.size(), ids.size(), e.getValue());
            ids.add(e.getKey());
        }
        m.setOnMenuItemClickListener(item -> { pick(KIND_SAVED, ids.get(item.getItemId()), category); return true; });
        m.show();
    }

    private void categoryMenu(View anchor) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Item it : items(0, null, null, "")) {
            String c = it.place.category;
            if (c != null && !c.isEmpty()) counts.merge(c, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            Toast.makeText(this, "No categories yet: they come with the places you look at", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        PopupMenu m = new PopupMenu(this, anchor);
        List<String> cats = new ArrayList<>();
        m.getMenu().add(0, 0, 0, "Any category");
        cats.add(null);
        for (Map.Entry<String, Integer> e : sorted) {
            m.getMenu().add(0, cats.size(), cats.size(), e.getKey());
            cats.add(e.getKey());
        }
        m.setOnMenuItemClickListener(item -> { pick(kind, savedList, cats.get(item.getItemId())); return true; });
        m.show();
    }

    private void historyMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        for (int i = 0; i < HISTORY_NAMES.length; i++) m.getMenu().add(0, i, i, HISTORY_NAMES[i]);
        m.setOnMenuItemClickListener(item -> { pick(HISTORY_KINDS[item.getItemId()], null, category); return true; });
        m.show();
    }

    /** A filter chosen: on the You page it opens Your places with it; on Your places it applies. */
    private void pick(int withKind, String withList, String withCategory) {
        if (!PLACES.equals(page)) { places(withKind, withList, withCategory, false); return; }
        kind = withKind;
        savedList = withList;
        category = withCategory;
        refilter();
    }

    private void refilter() {
        if (filterBar != null) {
            filterBar.removeAllViews();
            filterBar.addView(filterRow(false));
        }
        render();
    }

    /** "All saved places": a card with the newest saved photo, as in Maps. */
    private View allSavedCard() {
        List<SavedStore.Place> all = SavedStore.allSaved();
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(14), dp(4), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(card());
        g.setCornerRadius(dp(16));
        card.setBackground(g);
        SavedStore.Place withPhoto = null;
        for (SavedStore.Place p : all) if (!p.photo().isEmpty()) { withPhoto = p; break; }
        View thumb = withPhoto != null ? thumbnail(withPhoto, 44, 8)
                : iconTile(drawable(BOOKMARK_FILL, accent(), new PathIcon(PathIcon.BOOKMARK, accent())), 44, 8, fill());
        card.addView(thumb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(8), 0);
        col.addView(label("All saved places", 16, t1(), text));
        TextView sub = label("Private", 14, t3(), text);
        sub.setPadding(0, dp(4), 0, 0);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(menuButton(v -> go(SAVED, null)));
        card.setOnClickListener(v -> go(SAVED, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(20), dp(4), dp(20), dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private View listRow(String id, String name) {
        View r = listLikeRow(SaveSheet.listIcon(this, id, dark), name, "Private · " + count(SavedStore.countIn(id)),
                v -> go(LIST, id), v -> listMenu(id, name));
        r.setOnLongClickListener(v -> { listMenu(id, name); return true; });
        return r;
    }

    private View labeledRow() {
        int n = (SavedStore.home != null ? 1 : 0) + (SavedStore.work != null ? 1 : 0) + SavedStore.labels.size();
        return listLikeRow(new PathIcon(PathIcon.LABEL, accent()), "Labeled", "Private · " + count(n),
                v -> go(LABELED, null), v -> go(LABELED, null));
    }

    /** A list in Your saves: 24 dp icon, the name at 64 dp over "Private · N places" (if any), a menu (if any); an inset rule under it. */
    private View listLikeRow(Drawable icon, String name, String sub, View.OnClickListener click, View.OnClickListener menu) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(4), dp(10));
        r.setMinimumHeight(dp(68));
        ripple(r);
        ImageView i = new ImageView(this);
        i.setImageDrawable(icon);
        r.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, dp(8), 0);
        TextView t = label(name, 16, t1(), text);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null) {
            TextView s = label(sub, 12, t3(), text);
            s.setPadding(0, dp(4), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (menu != null) r.addView(menuButton(menu));
        else r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.setOnClickListener(click);
        wrap.addView(r);
        View rule = rule();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(dp(20), 0, dp(20), 0);
        wrap.addView(rule, lp);
        return wrap;
    }

    private View moreToggle() {
        TextView t = label(allLists ? "Less" : "More", 14, t1(), textMedium);
        Drawable arrow = new PathIcon(allLists ? PathIcon.EXPAND_LESS : PathIcon.EXPAND_MORE, t1());
        arrow.setBounds(0, 0, dp(20), dp(20));
        t.setCompoundDrawables(arrow, null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        t.setOnClickListener(v -> { allLists = !allLists; render(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        t.setLayoutParams(lp);
        return t;
    }

    /** A row like the lists' with a switch at its end; a tap anywhere flips it. */
    private View switchRow(Drawable icon, String name, boolean on, java.util.function.Consumer<Boolean> flipped) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(16), dp(10));
        r.setMinimumHeight(dp(68));
        ripple(r);
        ImageView i = new ImageView(this);
        i.setImageDrawable(icon);
        r.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = label(name, 16, t1(), text);
        t.setPadding(dp(20), 0, dp(8), 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(on);
        sw.setClickable(false);
        sw.setFocusable(false);
        r.addView(sw);
        r.setOnClickListener(v -> flipped.accept(!on));
        wrap.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(dp(20), 0, dp(20), 0);
        wrap.addView(rule(), lp);
        return wrap;
    }

    /** A round shortcut with its name under it (Maps' Timeline / Maps buttons). */
    private View shortcut(Drawable icon, String name, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(8));
        col.addView(iconTile(icon, 48, 24, tonal()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = label(name, 14, t1(), textMedium);
        t.setPadding(0, dp(8), 0, 0);
        t.setSingleLine(true);
        // At its own width: stretched to the column's, it was measured a hair short and lost its last letter.
        col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        col.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(8);
        col.setLayoutParams(lp);
        return col;
    }

    @Override View headerAction() {
        ImageView b;
        switch (page) {
            case LABELED:
                b = iconButton(drawable(ADD, t1(), new PathIcon(PathIcon.ADD, t1())), "Add label");
                b.setOnClickListener(v -> addLabel());
                return b;
            default:
                return null;
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Clear recent places?")
                // .setMessage("Every place you looked at, got directions to, called or shared is forgotten. Saved places stay.")
                .setPositiveButton("Clear", (d, w) -> { HistoryStore.clear(this); render(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Your places (See all) ------------------------------------------------------------

    @Override View pinnedTop() {
        if (!PLACES.equals(page)) return null;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        search = searchField("Search by name or note", s -> { query = s; render(); });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(dp(16), dp(4), dp(16), dp(4));
        top.addView(search, sl);
        filterBar = new FrameLayout(this);
        filterBar.addView(filterRow(false));
        top.addView(filterBar);
        return top;
    }

    private void renderPlaces() {
        List<Item> list = items(kind, savedList, category, query);
        if (list.isEmpty()) {
            if (!query.trim().isEmpty() || kind != 0 || category != null) body.addView(hint("No results found"));
            else if (!HistoryStore.enabled(this)) body.addView(hint("Remembering the places you look at is off. Turn it on under Your recent places on Local saved."));
            return;
        }
        for (Item it : list) body.addView(placeRow(it));
    }

    // ---- one list, all saved places, Labeled ------------------------------------------------

    private void renderList() {
        String name = SavedStore.lists.get(listId);
        if (name != null) listPage(SaveSheet.listIcon(this, listId, dark), name, SavedStore.inList(listId), true);
    }

    private void renderSaved() {
        listPage(drawable(BOOKMARK_FILL, accent(), new PathIcon(PathIcon.BOOKMARK, accent())), "All saved places",
                SavedStore.allSaved(), false);
    }

    /**
     * Maps' list page, measured off it: the list's icon with round menu / share / close buttons, the
     * name (28 sp) over its count, Add and Edit for a list, a band, Sort by, then the places as cards --
     * name and edit, rating, category and distance, note, photos -- between bands. An empty list is
     * just that: no "nothing here" text.
     */
    private void listPage(Drawable icon, String title, List<SavedStore.Place> places, boolean isList) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(20), dp(20), 0);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        top.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (isList) top.addView(circleButton(MORE, null, "More options", 32, this::listPageMenu));
        top.addView(circleButton(SHARE, null, "Share", 32, v -> shareList(title, places)), leftGap(12, 32));
        top.addView(circleButton(UiScreen.CLOSE, null, "Close", 32, v -> finish()), leftGap(12, 32));
        body.addView(top);

        TextView t = label(title, 28, t1(), sansMedium);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMargins(dp(20), dp(11), dp(20), 0);
        body.addView(t, tl);
        TextView c = label(count(places.size()), 12, t2(), sans);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(dp(20), dp(13), dp(20), 0);
        body.addView(c, cl);
        if (isList) {
            LinearLayout pills = new LinearLayout(this);
            pills.setPadding(dp(20), dp(20), dp(20), 0);
            // microG Maps adds places to lists in Maps, on the account; there is no local Add.
            boolean add = !Shapes.microgPatched();
            if (add) pills.addView(tonalPill(ADD_PLACE, "Add", v -> SavedPlaces.addPlaceTo(this, listId)));
            pills.addView(tonalPill(EDIT, "Edit", v -> editList(title)), add ? leftGap(8, 0) : new LinearLayout.LayoutParams(-2, -2));
            body.addView(pills);
        }
        body.addView(bandView());

        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setPadding(dp(14), dp(20), dp(20), dp(20));
        sortRow.addView(dropChip(sort == 0 ? "Sort by" : SORTS[sort], sort != 0, this::sortMenu));
        body.addView(sortRow);

        android.location.Location here = lastLocation();
        List<SavedStore.Place> sorted = sorted(places, here);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) body.addView(bandView());
            body.addView(card(sorted.get(i), here));
        }
    }

    private LinearLayout.LayoutParams leftGap(int gap, int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size > 0 ? dp(size) : -2, dp(size > 0 ? size : 40));
        lp.leftMargin = dp(gap);
        return lp;
    }

    /** A round grey button with a 20 dp icon, as along the top of Maps' sheets. */
    private ImageView circleButton(int iconId, String fallbackPath, String description, int size, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill());
        b.setBackground(g);
        b.setImageDrawable(drawable(iconId, ink(), fallbackPath != null ? new PathIcon(fallbackPath, ink()) : null));
        int pad = dp(size - 20) / 2;
        b.setPadding(pad, pad, pad, pad);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setContentDescription(description);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return b;
    }

    /** Maps' tonal pill ("Add", "Edit"): a 20 dp icon and the label, 40 dp tall. */
    private View tonalPill(int iconId, String s, View.OnClickListener click) {
        int ink = dark ? 0xFFB3ECF5 : 0xFF356974;
        TextView b = label(s, 14, ink, textMedium);
        Drawable icon = drawable(iconId, ink, null);
        icon.setBounds(0, 0, dp(20), dp(20));
        b.setCompoundDrawables(icon, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(20), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(tonal());
        g.setCornerRadius(dp(20));
        b.setBackground(g);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(40)));
        return b;
    }

    /** A place on a list page: name with an edit button, rating, category and distance, note, photos. */
    private View card(SavedStore.Place p, android.location.Location here) {
        HistoryStore.Entry seen = HistoryStore.find(p.key());
        if (seen != null) p.fillFrom(seen.asPlace());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(18), 0, dp(18));
        ripple(card);
        LinearLayout head = new LinearLayout(this);
        head.setPadding(dp(16), 0, dp(20), 0);
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView n = label(name(p), 18, t1(), textMedium);
        n.setPadding(0, dp(3), 0, 0);
        lines.addView(n);
        if (!Float.isNaN(p.rating)) lines.addView(ratingLine(p.rating, p.reviews));
        String dist = here != null && p.located() ? distance(meters(here, p)) : null;
        String line = p.category.isEmpty() ? (dist != null ? dist : "") : dist != null ? p.category + " · " + dist : p.category;
        if (!line.isEmpty()) lines.addView(secondLine(line));
        if (!p.note.isEmpty()) {
            TextView note = secondLine(p.note);
            note.setSingleLine(false);
            note.setMaxLines(3);
            lines.addView(note);
        }
        head.addView(lines, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(dp(40), dp(40));
        el.leftMargin = dp(12);
        ImageView edit = circleButton(EDIT, null, "Edit", 40, v -> SaveSheet.show(this, p, this::render));
        head.addView(edit, el);
        card.addView(head);
        if (!p.photos.isEmpty()) card.addView(photoStrip(p));
        card.setOnClickListener(v -> SavedPlaces.open(this, p));
        card.setOnLongClickListener(v -> { placeMenu(p, seen); return true; });
        return card;
    }

    /** "4.2 ★★★★★ (229)", Maps' gold stars. */
    private View ratingLine(float rating, int reviews) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, 0);
        r.addView(label(String.format(Locale.getDefault(), "%.1f", rating), 14, t2(), text));
        int full = Math.round(rating);
        for (int i = 0; i < 5; i++) {
            ImageView star = new ImageView(this);
            star.setImageDrawable(new PathIcon(STAR, i < full ? 0xFFF1BF42 : dark ? 0xFF5F6368 : 0xFFDADCDF));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(15), dp(15));
            if (i == 0) lp.leftMargin = dp(4);
            r.addView(star, lp);
        }
        if (reviews > 0) {
            TextView c = label("(" + java.text.NumberFormat.getInstance().format(reviews) + ")", 14, t2(), text);
            c.setPadding(dp(4), 0, 0, 0);
            r.addView(c);
        }
        return r;
    }

    /** The place's photos, 134 x 142 dp with 16 dp corners, side by side, as on Maps' list cards. */
    private View photoStrip(SavedStore.Place p) {
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(20), dp(14), dp(20), 0);
        for (int i = 0; i < p.photos.size(); i++) {
            ImageView v = new ImageView(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill());
            g.setCornerRadius(dp(16));
            v.setBackground(g);
            v.setClipToOutline(true);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setOnClickListener(x -> SavedPlaces.open(this, p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(134), dp(142));
            if (i > 0) lp.leftMargin = dp(8);
            strip.addView(v, lp);
            Thumbs.loadInto(v, p.photos.get(i), dp(142), () -> {});
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(strip);
        return hs;
    }

    private void sortMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        for (int i = 0; i < SORTS.length; i++) m.getMenu().add(0, i, i, SORTS[i]);
        m.setOnMenuItemClickListener(item -> {
            sort = item.getItemId();
            if (sort == 1 && lastLocation() == null) {
                Toast.makeText(this, "Distance needs your location", Toast.LENGTH_SHORT).show();
                sort = 0;
            }
            render();
            return true;
        });
        m.show();
    }

    /** [places] come newest first; Distance puts the nearest first, Editor's order the first added. */
    private List<SavedStore.Place> sorted(List<SavedStore.Place> places, android.location.Location here) {
        List<SavedStore.Place> out = new ArrayList<>(places);
        // Places without a position yet (from a Takeout list) last.
        if (sort == 1 && here != null) out.sort((a, b) -> a.located() != b.located() ? (a.located() ? -1 : 1)
                : a.located() ? Float.compare(meters(here, a), meters(here, b)) : 0);
        else if (sort == 2) java.util.Collections.reverse(out);
        return out;
    }

    private void listPageMenu(View anchor) {
        String name = SavedStore.lists.get(listId);
        if (name == null) return;
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 0, 0, "Share list");
        m.getMenu().add(0, 1, 1, "Rename");
        if (!SavedStore.isDefault(listId)) m.getMenu().add(0, 2, 2, "Delete list");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) shareList(name, SavedStore.inList(listId));
            else if (item.getItemId() == 1) SavedPlaces.newList(this, n -> { SavedStore.renameList(this, listId, n); render(); });
            else deleteList(listId, name);
            return true;
        });
        m.show();
    }

    /** The list as text: its name, then each place with a link that opens it in Maps. */
    private void shareList(String title, List<SavedStore.Place> places) {
        StringBuilder b = new StringBuilder(title).append('\n');
        for (SavedStore.Place p : places) b.append("\n").append(name(p)).append('\n').append(SavedPlaces.placeUrl(p)).append('\n');
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, b.toString().trim());
        startActivity(Intent.createChooser(send, "Share " + title));
    }

    /** Edit on a list: rename it, take places out of it, or delete it (one of the user's own). */
    private void editList(String title) {
        List<String> items = new ArrayList<>();
        items.add("Rename");
        items.add("Remove places…");
        if (!SavedStore.isDefault(listId)) items.add("Delete list");
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle(title)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) SavedPlaces.newList(this, n -> { SavedStore.renameList(this, listId, n); render(); });
                    else if (which == 1) removePlaces();
                    else deleteList(listId, title);
                })
                .show();
    }

    private void removePlaces() {
        List<SavedStore.Place> in = SavedStore.inList(listId);
        if (in.isEmpty()) {
            Toast.makeText(this, "This list has no places", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[in.size()];
        boolean[] ticked = new boolean[in.size()];
        for (int i = 0; i < names.length; i++) names[i] = name(in.get(i));
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Remove places")
                .setMultiChoiceItems(names, ticked, (d, which, on) -> ticked[which] = on)
                .setPositiveButton("Remove", (d, w) -> {
                    for (int i = 0; i < ticked.length; i++) {
                        if (!ticked[i]) continue;
                        java.util.Set<String> lists = new java.util.LinkedHashSet<>(in.get(i).lists);
                        lists.remove(listId);
                        SavedStore.setLists(this, in.get(i), lists);
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteList(String id, String name) {
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Delete " + name + "?")
                .setMessage("Its places stay in any other lists they are in.")
                .setPositiveButton("Delete", (d, w) -> {
                    SavedStore.deleteList(this, id);
                    if (LIST.equals(page)) finish(); else render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static float meters(android.location.Location here, SavedStore.Place p) {
        float[] d = new float[1];
        android.location.Location.distanceBetween(here.getLatitude(), here.getLongitude(), p.lat, p.lng, d);
        return d[0];
    }

    private android.location.Location lastLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
        if (lm == null) return null;
        android.location.Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                android.location.Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /** "4,819 mi" / "350 m": miles where Maps uses them. */
    private static String distance(float meters) {
        String country = Locale.getDefault().getCountry();
        java.text.NumberFormat f = java.text.NumberFormat.getInstance();
        if ("US".equals(country) || "LR".equals(country) || "MM".equals(country)) {
            float miles = meters / 1609.344f;
            if (miles < 0.1f) return f.format(Math.round(meters * 3.28084f)) + " ft";
            f.setMaximumFractionDigits(miles < 10 ? 1 : 0);
            return f.format(miles) + " mi";
        }
        if (meters < 1000) return f.format(Math.round(meters)) + " m";
        f.setMaximumFractionDigits(meters < 10000 ? 1 : 0);
        return f.format(meters / 1000f) + " km";
    }

    private Item savedItem(SavedStore.Place p) {
        Item it = new Item();
        it.place = p;
        it.saved = true;
        it.kind = KIND_SAVED;
        it.time = p.added;
        it.history = HistoryStore.find(p.key());
        return it;
    }

    private void renderLabeled() {
        body.addView(aliasRow("Home", SavedStore.home, true));
        body.addView(aliasRow("Work", SavedStore.work, false));
        for (Map.Entry<String, SavedStore.Place> e : new ArrayList<>(SavedStore.labels.entrySet())) {
            body.addView(labelRow(e.getKey(), e.getValue()));
        }
        body.addView(hint("Add a label with + above, or from any place's menu."));
    }

    private View aliasRow(String label, SavedStore.Place p, boolean home) {
        Drawable icon = drawable(home ? HOME : WORK, t2(), new PathIcon(PathIcon.LABEL, t2()));
        LinearLayout r = iconRow(icon, label, p != null ? name(p) : "Not set. Choose Set as " + label + " in a place's menu.");
        r.setOnClickListener(v -> {
            if (p != null) SavedPlaces.directions(this, p);
            else Toast.makeText(this, "Open a place's menu (⋮), then Set as " + label, Toast.LENGTH_SHORT).show();
        });
        if (p != null) r.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                    .setTitle(label + ": " + name(p))
                    .setItems(new String[]{"Directions", "Open", "Clear " + label}, (d, which) -> {
                        if (which == 0) SavedPlaces.directions(this, p);
                        else if (which == 1) SavedPlaces.open(this, p);
                        else { if (home) SavedStore.setHome(this, null); else SavedStore.setWork(this, null); render(); }
                    })
                    .show();
            return true;
        });
        return r;
    }

    private View labelRow(String label, SavedStore.Place p) {
        LinearLayout r = iconRow(new PathIcon(PathIcon.LABEL, t2()), label, name(p));
        r.setOnClickListener(v -> SavedPlaces.open(this, p));
        r.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                    .setTitle(label + ": " + name(p))
                    .setItems(new String[]{"Open", "Directions", "Rename label", "Remove label"}, (d, which) -> {
                        if (which == 0) SavedPlaces.open(this, p);
                        else if (which == 1) SavedPlaces.directions(this, p);
                        else if (which == 2) labelDialog(p, label);
                        else { SavedStore.removeLabel(this, label); render(); }
                    })
                    .show();
            return true;
        });
        return r;
    }

    /** + on Labeled: pick one of the saved or recent places, then name it. */
    private void addLabel() {
        List<SavedStore.Place> choices = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (SavedStore.Place p : SavedStore.allSaved()) if (seen.put(p.key(), true) == null) choices.add(p);
        for (HistoryStore.Entry e : HistoryStore.newestFirst()) if (seen.put(e.key(), true) == null) choices.add(e.asPlace());
        if (choices.isEmpty()) {
            Toast.makeText(this, "Save or look at a place first", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = name(choices.get(i));
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Label which place?")
                .setItems(names, (d, which) -> labelDialog(choices.get(which), null))
                .show();
    }

    /** Names [p]; [old] is the label being renamed, or null. "Home" and "Work" set those. */
    private void labelDialog(SavedStore.Place p, String old) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setHint("Label, like Gym");
        if (old != null) field.setText(old);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field);
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle(old != null ? "Rename label" : "Label " + name(p))
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String label = field.getText().toString().trim();
                    if (label.isEmpty()) return;
                    if (old != null) SavedStore.removeLabel(this, old);
                    if (label.equalsIgnoreCase("home")) SavedStore.setHome(this, SavedStore.aliasOf(p));
                    else if (label.equalsIgnoreCase("work")) SavedStore.setWork(this, SavedStore.aliasOf(p));
                    else SavedStore.setLabel(this, label, p);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- place rows ---------------------------------------------------------------------------

    /** A recent place or save: what happened last, and when. */
    private static final class Item {
        SavedStore.Place place;
        HistoryStore.Entry history;
        boolean saved;
        int kind;
        long time;
    }

    /**
     * Recent places and saves merged, newest first. [k] 0 = everything, KIND_HISTORY = any use,
     * a HistoryStore kind = that use, KIND_SAVED = saved ([list] narrows it); [cat] and [q] search too.
     */
    private List<Item> items(int k, String list, String cat, String q) {
        Map<String, Item> byKey = new LinkedHashMap<>();
        if (k != KIND_SAVED) for (HistoryStore.Entry e : HistoryStore.newestFirst()) {
            long t = k == 0 || k == KIND_HISTORY ? e.last : e.at[HistoryStore.index(k)];
            if (t <= 0) continue;
            Item it = new Item();
            it.history = e;
            it.place = e.asPlace();
            it.kind = k == 0 || k == KIND_HISTORY ? e.lastKind() : k;
            it.time = t;
            byKey.put(e.key(), it);
        }
        for (SavedStore.Place p : SavedStore.allSaved()) {
            Item it = byKey.get(p.key());
            if (it == null) {
                if (k != 0 && k != KIND_SAVED) continue;
                if (k == KIND_SAVED && list != null && !p.lists.contains(list)) continue;
                it = new Item();
                it.history = HistoryStore.find(p.key());
                byKey.put(p.key(), it);
            }
            it.saved = true;
            SavedStore.Place seen = it.place;
            it.place = p;
            // A saved place takes what its history knows when it has no category or photo of its own.
            p.fillFrom(seen);
            if ((k == 0 || k == KIND_SAVED) && p.added >= it.time) { it.kind = KIND_SAVED; it.time = p.added; }
        }
        List<Item> out = new ArrayList<>(byKey.values());
        String ql = q.trim().toLowerCase(Locale.getDefault());
        out.removeIf(it -> (cat != null && !cat.equalsIgnoreCase(it.place.category))
                || (!ql.isEmpty() && !matches(it, ql)));
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    private static boolean matches(Item it, String q) {
        Locale l = Locale.getDefault();
        if (it.place.name != null && it.place.name.toLowerCase(l).contains(q)) return true;
        if (it.place.note != null && it.place.note.toLowerCase(l).contains(q)) return true;
        if (it.place.category != null && it.place.category.toLowerCase(l).contains(q)) return true;
        for (String label : SavedStore.labelsFor(it.place)) if (label.toLowerCase(l).contains(q)) return true;
        return false;
    }

    /**
     * A place as Maps' recent places show one: its photo (or a pin) 56 dp at 20 dp, the name, the
     * category and "Viewed · 4h" from 92 dp, then a round Save button and a menu.
     */
    private View placeRow(Item it) {
        SavedStore.Place p = it.place;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(12), dp(4), dp(12));
        r.setMinimumHeight(dp(88));
        ripple(r);
        r.addView(thumbnail(p, 56, 12), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(8), 0);
        TextView n = label(name(p), 16, t1(), text);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(n);
        if (!p.category.isEmpty()) col.addView(secondLine(p.category));
        String what = whatHappened(it) + " · " + ago(it.time);
        col.addView(secondLine(it.saved && !p.note.isEmpty() ? what + " · " + p.note : what));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean saved = SavedStore.find(p.key()) != null;
        ImageView save = iconButton(saved ? drawable(BOOKMARK_FILL, accent(), new PathIcon(PathIcon.BOOKMARK, accent()))
                : drawable(BOOKMARK, ink(), new PathIcon(PathIcon.BOOKMARK, ink())), saved ? "Saved" : "Save");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(fill());
        save.setBackground(circle);
        save.setPadding(dp(10), dp(10), dp(10), dp(10));
        save.setScaleType(ImageView.ScaleType.FIT_CENTER);
        save.setOnClickListener(v -> {
            SavedStore.Place stored = SavedStore.find(p.key());
            SaveSheet.show(this, stored != null ? stored : p, this::render);
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(40), dp(40));
        sl.rightMargin = dp(4);
        r.addView(save, sl);
        r.addView(menuButton(v -> placeMenu(p, it.history)));
        r.setOnClickListener(v -> SavedPlaces.open(this, p));
        r.setOnLongClickListener(v -> { placeMenu(p, it.history); return true; });
        return r;
    }

    private TextView secondLine(String s) {
        TextView t = label(s, 14, t2(), text);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private String whatHappened(Item it) {
        switch (it.kind) {
            case HistoryStore.DIRECTIONS: return "Got directions";
            case HistoryStore.CALLED: return "Called";
            case HistoryStore.SHARED: return "Shared";
            case KIND_SAVED: return savedWhere(it.place);
            default: return "Viewed";
        }
    }

    /** The place's photo, rounded [radius] dp, or Maps' pin on grey until (or unless) there is one. */
    private View thumbnail(SavedStore.Place p, int size, int radius) {
        ImageView v = new ImageView(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill());
        g.setCornerRadius(dp(radius));
        v.setBackground(g);
        v.setClipToOutline(true);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Drawable pin = new PathIcon(PathIcon.PIN, ink());
        int inset = dp(size) / 4;
        v.setPadding(inset, inset, inset, inset);
        v.setImageDrawable(pin);
        if (!p.photo().isEmpty()) {
            Thumbs.loadInto(v, p.photo(), Math.max(dp(size), 96), () -> {
                v.setPadding(0, 0, 0, 0);
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            });
        }
        return v;
    }

    /** An icon centred on a rounded (or round) tile. */
    private View iconTile(Drawable icon, int size, int radius, int color) {
        ImageView v = new ImageView(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        v.setBackground(g);
        v.setImageDrawable(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = dp(size) / 4;
        v.setPadding(inset, inset, inset, inset);
        return v;
    }

    private ImageView menuButton(View.OnClickListener click) {
        ImageView m = iconButton(drawable(MORE, ink(), null), "More options");
        m.setOnClickListener(click);
        m.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
        return m;
    }

    /** Open, directions, save, label, Home / Work, forget: for any place on these pages. */
    private void placeMenu(SavedStore.Place p, HistoryStore.Entry history) {
        SavedStore.Place stored = SavedStore.find(p.key());
        boolean home = SavedStore.same(SavedStore.home, p), work = SavedStore.same(SavedStore.work, p);
        List<String> names = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        names.add("Open"); acts.add(() -> SavedPlaces.open(this, p));
        names.add("Directions"); acts.add(() -> SavedPlaces.directions(this, p));
        names.add(stored != null ? "Lists and note…" : "Save…");
        acts.add(() -> SaveSheet.show(this, stored != null ? stored : p, this::render));
        names.add("Label…"); acts.add(() -> labelDialog(p, null));
        names.add(home ? "Clear Home" : "Set as Home");
        acts.add(() -> { SavedStore.setHome(this, home ? null : SavedStore.aliasOf(p)); render(); });
        names.add(work ? "Clear Work" : "Set as Work");
        acts.add(() -> { SavedStore.setWork(this, work ? null : SavedStore.aliasOf(p)); render(); });
        if (history != null) {
            names.add("Delete from recent places");
            acts.add(() -> { HistoryStore.delete(this, history.key()); render(); });
        }
        if (stored != null) {
            names.add("Unsave");
            acts.add(() -> { SavedStore.unsave(this, stored); render(); });
        }
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle(name(p))
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    private void listMenu(String id, String name) {
        if (name == null) return;
        List<String> names = new ArrayList<>();
        names.add("Open");
        names.add("Rename");
        if (!SavedStore.isDefault(id)) names.add("Delete list");
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle(name)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) { if (!LIST.equals(page)) go(LIST, id); }
                    else if (which == 1) SavedPlaces.newList(this, n -> { SavedStore.renameList(this, id, n); if (LIST.equals(page)) recreate(); else render(); });
                    else new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                            .setTitle("Delete " + name + "?")
                            .setMessage("Its places stay in any other lists they are in.")
                            .setPositiveButton("Delete", (d2, w) -> {
                                SavedStore.deleteList(this, id);
                                if (LIST.equals(page)) finish(); else render();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .show();
    }

    private String savedWhere(SavedStore.Place p) {
        if (p.lists.isEmpty()) return "Saved";
        if (p.lists.size() == 1) {
            String n = SavedStore.lists.get(p.lists.iterator().next());
            return n != null ? "Saved in " + n : "Saved";
        }
        return "Saved in " + p.lists.size() + " lists";
    }

    private String nameOfList(String id) {
        String n = SavedStore.lists.get(id);
        return n != null ? n : "Saved";
    }

    // ---- export / import ------------------------------------------------------------------

    private void exportMenu() {
        new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Export")
                .setItems(new String[]{"Backup file (everything, to import here later)",
                        "Saved places as KML (OsmAnd, Organic Maps, Google Earth)", "Recent places as KML"},
                        (d, which) -> createDocument(which == 0 ? EXPORT_JSON : which == 1 ? EXPORT_KML : EXPORT_RECENT))
                .show();
    }

    private void createDocument(int what) {
        boolean json = what == EXPORT_JSON;
        String file = json ? "ungoogled-maps-backup.json" : what == EXPORT_KML ? "ungoogled-maps-saved.kml" : "ungoogled-maps-recent.kml";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(json ? "application/json" : "application/vnd.google-earth.kml+xml")
                .putExtra(Intent.EXTRA_TITLE, file);
        startActivityForResult(i, what);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == IMPORT) {
            importChosen(data);
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            if (request == EXPORT_JSON) SavedStore.exportJson(this, uri);
            else if (request == EXPORT_KML) SavedStore.exportKml(this, uri);
            else if (request == EXPORT_RECENT) SavedStore.exportRecentKml(this, uri);
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "That didn't work: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The files picked for Import, several at once if wanted: Google Takeout's saved lists (a CSV
     * each), its whole .zip, Saved Places.json, KML or a backup. Read off the main thread -- a
     * Takeout .zip can be large.
     */
    private void importChosen(Intent data) {
        List<Uri> uris = new ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            String title, message;
            try {
                SavedStore.Imported r = SavedStore.importFiles(this, uris);
                title = r.places == 0 ? "Nothing new" : "Imported";
                message = r.places == 0 ? "Everything in " + (r.files == 1 ? "that file" : "those files") + " is here already."
                        : "Added " + count(r.places) + (r.lists == 0 ? "" : r.lists == 1 ? " and 1 list" : " and " + r.lists + " lists") + ".";
                if (r.unlocated > 0) {
                    message += "\n\n" + (r.unlocated == 1 ? "1 place has" : r.unlocated + " places have") + " no position yet: Google "
                            + "Takeout's lists leave it out. Each place gets it the first time you open it in Maps; until then "
                            + "it shows no distance, and Directions opens the place first.";
                }
            } catch (Throwable t) {
                title = "That didn't work";
                message = String.valueOf(t.getMessage());
            }
            String ti = title, m = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render();
                new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this)).setTitle(ti).setMessage(m).setPositiveButton("OK", null).show();
            });
        }, "UA-import").start();
    }

    // ---- words ----------------------------------------------------------------------------

    private static String count(int n) {
        return n == 1 ? "1 place" : n + " places";
    }

    private static String name(SavedStore.Place p) {
        return p.name != null && !p.name.isEmpty() ? p.name : String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng);
    }

    /** Maps' short "when": 5m, 4h, 1d, 2w, then a date. */
    private static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < DateUtils.MINUTE_IN_MILLIS) return "now";
        if (d < DateUtils.HOUR_IN_MILLIS) return d / DateUtils.MINUTE_IN_MILLIS + "m";
        if (d < DateUtils.DAY_IN_MILLIS) return d / DateUtils.HOUR_IN_MILLIS + "h";
        if (d < DateUtils.WEEK_IN_MILLIS) return d / DateUtils.DAY_IN_MILLIS + "d";
        if (d < 5 * DateUtils.WEEK_IN_MILLIS) return d / DateUtils.WEEK_IN_MILLIS + "w";
        Calendar then = Calendar.getInstance(), now = Calendar.getInstance();
        then.setTimeInMillis(t);
        String pattern = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "MMM d" : "MMM d, yyyy";
        return new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(new java.util.Date(t));
    }

    private void ripple(View v) {
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) v.setBackgroundResource(ripple.resourceId);
    }
}
