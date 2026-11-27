package org.ungoogled.samsungcontacts;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Contact groups as Samsung's contacts provider serves them, answered from Android's own groups and group memberships.
 * Samsung's provider has group lists with member counts (groups_list/assigned per account, groups_list/aggregated
 * merged by title, groups_list/not_assigned), member lists (groups/#/contacts, groups/title/NAME/contacts,
 * groups/not_assigned/contacts and their contacts_filter searches), memberships added by group title
 * (data/group_membership, data/group_membership_only_raw_contact_id), a "Groups" row at the top of the contact list
 * (dummyGroup) and one phone number or email per contact (data_groupby, a group's message and email): off One UI the
 * app listed no groups, saved no members and showed none. Only Android's standard columns are written; the emergency
 * contacts group (Samsung's own table) is left to the app.
 */
final class Groups {
    private static final String TAG = "UA";
    static final String MEMBERSHIP = "vnd.android.cursor.item/group_membership";
    private static final String AGG_NAME = "vnd.sec.contact.agg.account_name";
    private static final String AGG_TYPE = "vnd.sec.contact.agg.account_type";
    private static final String NOT_ASSIGNED = "Not Assigned";
    private static final String ICE = "ICE";
    private static final Uri GROUPS = Uri.parse("content://com.android.contacts/groups");
    private static final Uri DATA = Uri.parse("content://com.android.contacts/data");
    private static final Uri RAW_CONTACTS = Uri.parse("content://com.android.contacts/raw_contacts");
    private static final Uri CONTACTS = Uri.parse("content://com.android.contacts/contacts");
    private static final Uri CONTACTS_FILTER = Uri.parse("content://com.android.contacts/contacts/filter");
    private static final String TITLES = "android.provider.extra.ADDRESS_BOOK_INDEX_TITLES";
    private static final String COUNTS = "android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS";

    /** Account types Samsung's provider makes groups in (it keeps the others in a pseudo account Android lacks). */
    private static final Set<String> GROUP_ACCOUNTS = new HashSet<>(Arrays.asList("com.google", "vnd.sec.contact.phone",
            "com.android.nttdocomo", "com.osp.app.signin", "com.samsung.android.exchange", "com.skt.prod",
            "vnd.tmobileus.contact.phone", "com.kddi.ast.auoneid", "com.android.sharepoint", "com.google.android.gm.exchange"));
    private static final Set<String> SIM = new HashSet<>(Arrays.asList("vnd.sec.contact.sim", "vnd.sec.contact.sim2"));
    /** Query parameters only Samsung's provider reads: not passed on to Android's contacts list. */
    private static final Set<String> SAMSUNG_PARAMS = new HashSet<>(Arrays.asList("account_name", "account_type", "data_set",
            "account_exclude", "title", "has_phone_number", "has_email", "emergency", "dummyGroup", "secDummyStarred"));

    /** The columns of Samsung's group lists. */
    private static final String[] LIST_COLUMNS = {"_id", "title", "groups_count", "system_id", "account_name", "account_type",
            "data_set", "auto_add", "reverse_auto_add", "favorites", "should_sync", "group_visible", "groups_type", "group_order",
            "group_default_order", "sec_custom_dormant_group"};

    private Groups() {
    }

    // Paths

    /** Whether this is one of Samsung's group paths, or a contact list asking for the Groups row. */
    static boolean handles(Uri uri) {
        if (uri == null || !"com.android.contacts".equals(uri.getAuthority())) return false;
        List<String> p = uri.getPathSegments();
        if (p.isEmpty()) return false;
        switch (p.get(0)) {
            case "groups_list":
                return p.size() == 2;
            case "groups":
                // The emergency contacts group lives in Samsung's own table: left to the app, as before.
                if ("true".equals(uri.getQueryParameter("emergency"))) return false;
                return members(p) >= 0;
            case "contacts":
                if (p.size() >= 2 && ("account".equals(p.get(1)) || "account_filter".equals(p.get(1)))) return true;
                return "true".equals(uri.getQueryParameter("dummyGroup"));
            case "data_groupby":
                return p.size() == 1 && "contact_id".equals(uri.getQueryParameter("groupby"));
            default:
                return false;
        }
    }

    /**
     * The index of "contacts" or "contacts_filter" in groups/#/contacts, groups/not_assigned/contacts or
     * groups/title/NAME/contacts (each also as contacts_filter/TEXT), or -1.
     */
    private static int members(List<String> p) {
        boolean titled = p.size() > 1 && "title".equals(p.get(1));
        int at = titled ? 3 : 2;
        if (p.size() <= at) return -1;
        if (!titled && !"not_assigned".equals(p.get(1)) && !isId(p.get(1))) return -1;
        String s = p.get(at);
        if ("contacts".equals(s) && p.size() == at + 1) return at;
        if ("contacts_filter".equals(s) && p.size() == at + 2) return at;
        return -1;
    }

    private static boolean isId(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Whether this is Samsung's insert of a membership by group title. */
    static boolean isMembership(Uri uri) {
        if (uri == null || !"com.android.contacts".equals(uri.getAuthority())) return false;
        List<String> p = uri.getPathSegments();
        return p.size() == 2 && "data".equals(p.get(0))
                && ("group_membership".equals(p.get(1)) || "group_membership_only_raw_contact_id".equals(p.get(1)));
    }

    // Queries

    static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                        CancellationSignal signal) {
        List<String> p = uri.getPathSegments();
        try {
            switch (p.get(0)) {
                case "groups_list":
                    return list(r, uri, p.get(1), projection, selection, args, sort);
                case "data_groupby":
                    return representatives(r, uri, projection, selection, args, sort, signal);
                case "contacts":
                    if (p.size() >= 2 && ("account".equals(p.get(1)) || "account_filter".equals(p.get(1)))) {
                        // Every contact; the app's selection picks the account.
                        return contacts(r, uri, null, filterText(p, 1), projection, selection, args, sort, signal);
                    }
                    return withGroupsRow(r, uri, projection, selection, args, sort, signal);
                default:
                    int at = members(p);
                    String text = filterText(p, at);
                    String clause;
                    if ("not_assigned".equals(p.get(1))) {
                        clause = "_id NOT IN (SELECT contact_id FROM view_data WHERE mimetype='" + MEMBERSHIP
                                + "' AND contact_id IS NOT NULL AND data1 IN (" + join(assigning(groups(r))) + "))";
                    } else {
                        Set<Long> ids = new HashSet<>();
                        if ("title".equals(p.get(1))) {
                            for (Group g : titled(groups(r), p.get(2), uri)) ids.add(g.id);
                        } else {
                            ids.add(Long.parseLong(p.get(1)));
                        }
                        clause = "_id IN (SELECT contact_id FROM view_data WHERE mimetype='" + MEMBERSHIP + "' AND data1 IN ("
                                + join(ids) + "))";
                    }
                    return contacts(r, uri, clause, text, projection, selection, args, sort, signal);
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            Log.w(TAG, "groups query failed: " + uri + ": " + e);
            return null;
        }
    }

    /** The search text after "contacts_filter" (or "account_filter"), or null for the whole list. */
    private static String filterText(List<String> p, int at) {
        if (at < 0 || at + 1 >= p.size() || !p.get(at).endsWith("_filter")) return null;
        return p.get(at + 1);
    }

    /** The contacts matching a clause through Android's contacts list (or its search), with the app's own conditions. */
    private static Cursor contacts(ContentResolver r, Uri uri, String clause, String text, String[] projection,
                                   String selection, String[] args, String sort, CancellationSignal signal) {
        Uri.Builder b = (text == null ? CONTACTS : Uri.withAppendedPath(CONTACTS_FILTER, Uri.encode(text))).buildUpon();
        for (String name : uri.getQueryParameterNames()) {
            if (SAMSUNG_PARAMS.contains(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        return ProviderCalls.query(r, b.build(), projection, and(clause, contactFilter(uri, "_id"), selection), args, sort, signal);
    }

    /** Samsung's has_phone_number / has_email parameters as a condition on a contact id column, or null. */
    private static String contactFilter(Uri uri, String column) {
        boolean phone = uri.getBooleanQueryParameter("has_phone_number", false);
        boolean email = uri.getBooleanQueryParameter("has_email", false);
        String withPhone = column + " IN (SELECT contact_id FROM view_data WHERE mimetype='vnd.android.cursor.item/phone_v2')";
        String withEmail = column + " IN (SELECT contact_id FROM view_data WHERE mimetype='vnd.android.cursor.item/email_v2')";
        if (phone && email) return "(" + withPhone + " OR " + withEmail + ")";
        if (phone) return withPhone;
        if (email) return withEmail;
        return null;
    }

    private static String and(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" AND ");
            sb.append('(').append(part).append(')');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String join(Set<Long> ids) {
        if (ids.isEmpty()) return "NULL";
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** The contact list with Samsung's "Groups" row first (its _id is -1; the app opens the group list from it). */
    private static Cursor withGroupsRow(ContentResolver r, Uri uri, String[] projection, String selection, String[] args,
                                        String sort, CancellationSignal signal) {
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("dummyGroup".equals(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        Cursor list = ProviderCalls.query(r, b.build(), projection, selection, args, sort, signal);
        if (list == null) return null;
        String[] columns = list.getColumnNames();
        Object[] row = new Object[columns.length];
        int id = list.getColumnIndex("_id");
        if (columns.length > 0) row[id >= 0 ? id : 0] = -1L;
        MatrixCursor groupsRow = new MatrixCursor(columns, 1);
        groupsRow.addRow(row);
        MergeCursor merged = new MergeCursor(new Cursor[]{groupsRow, list});
        Bundle extras = new Bundle(list.getExtras());
        String[] titles = extras.getStringArray(TITLES);
        int[] counts = extras.getIntArray(COUNTS);
        if (titles != null && counts != null && titles.length == counts.length) {
            // The provider's index names the row's section GROUPS, before the letters.
            String[] t = new String[titles.length + 1];
            int[] c = new int[counts.length + 1];
            t[0] = "GROUPS";
            c[0] = 1;
            System.arraycopy(titles, 0, t, 1, titles.length);
            System.arraycopy(counts, 0, c, 1, counts.length);
            extras.putStringArray(TITLES, t);
            extras.putIntArray(COUNTS, c);
        }
        merged.setExtras(extras);
        return merged;
    }

    /**
     * Samsung's data_groupby?groupby=contact_id: one data row per contact (a group's members to message or email, the
     * second line under names), preferring the first of the 2linedisplay_mimetypes, then the default one, then the oldest.
     */
    private static Cursor representatives(ContentResolver r, Uri uri, String[] projection, String selection, String[] args,
                                          String sort, CancellationSignal signal) {
        List<String> order = new ArrayList<>();
        String types = uri.getQueryParameter("2linedisplay_mimetypes");
        if (types != null) {
            for (String t : types.split(",")) if (!t.trim().isEmpty()) order.add(t.trim());
        }
        Uri.Builder b = DATA.buildUpon();
        for (String name : uri.getQueryParameterNames()) {
            if ("groupby".equals(name) || "2linedisplay_mimetypes".equals(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        Uri data = b.build();
        String mimetypes = null;
        if (!order.isEmpty()) {
            StringBuilder in = new StringBuilder("mimetype IN (");
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) in.append(',');
                in.append(android.database.DatabaseUtils.sqlEscapeString(order.get(i)));
            }
            mimetypes = in.append(')').toString();
        }
        Map<Long, long[]> best = new HashMap<>(); // contact -> {data id, rank}
        try (Cursor c = ProviderCalls.query(r, data, new String[]{"_id", "contact_id", "mimetype", "is_super_primary"},
                and(mimetypes, selection), args, "_id ASC", signal)) {
            while (c != null && c.moveToNext()) {
                if (c.isNull(1)) continue;
                int type = order.indexOf(c.getString(2));
                long rank = (type < 0 ? order.size() : type) * 2L + (c.getInt(3) != 0 ? 0 : 1);
                long[] seen = best.get(c.getLong(1));
                if (seen == null || rank < seen[1]) best.put(c.getLong(1), new long[]{c.getLong(0), rank});
            }
        }
        Set<Long> ids = new HashSet<>();
        for (long[] v : best.values()) ids.add(v[0]);
        return ProviderCalls.query(r, data, projection, "_id IN (" + join(ids) + ")", null, sort, signal);
    }

    // Group lists

    private static final class Group {
        long id;
        String title, systemId, accountName, accountType, dataSet;
        long autoAdd, favorites, shouldSync, visible;
    }

    /** Android's groups, without deleted ones. */
    private static List<Group> groups(ContentResolver r) {
        List<Group> out = new ArrayList<>();
        String[] columns = {"_id", "title", "system_id", "account_name", "account_type", "data_set", "auto_add", "favorites",
                "should_sync", "group_visible"};
        try (Cursor c = r.query(GROUPS, columns, "deleted=0", null, null)) {
            while (c != null && c.moveToNext()) {
                Group g = new Group();
                g.id = c.getLong(0);
                g.title = c.getString(1);
                g.systemId = c.getString(2);
                g.accountName = c.getString(3);
                g.accountType = c.getString(4);
                g.dataSet = c.getString(5);
                g.autoAdd = c.getLong(6);
                g.favorites = c.getLong(7);
                g.shouldSync = c.getLong(8);
                g.visible = c.getLong(9);
                out.add(g);
            }
        }
        return out;
    }

    /** The groups that make a contact "assigned": all but Google's My Contacts and starred groups, as on One UI. */
    private static Set<Long> assigning(List<Group> groups) {
        Set<Long> ids = new HashSet<>();
        for (Group g : groups) {
            if ("Contacts".equals(g.systemId) || "Starred in Android".equals(g.title)) continue;
            ids.add(g.id);
        }
        return ids;
    }

    /** The accounts named by repeated account_name / account_type parameters. */
    private static List<String[]> accounts(Uri uri) {
        List<String> names = uri.getQueryParameters("account_name");
        List<String> types = uri.getQueryParameters("account_type");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(names.size(), types.size()); i++) out.add(new String[]{names.get(i), types.get(i)});
        return out;
    }

    private static boolean in(List<String[]> accounts, Group g) {
        for (String[] a : accounts) {
            if (Objects.equals(a[0], g.accountName) && Objects.equals(a[1], g.accountType)) return true;
        }
        return false;
    }

    /** The groups behind a title, as groups/title/NAME/contacts reads its account parameters. */
    private static List<Group> titled(List<Group> groups, String title, Uri uri) {
        List<String[]> accounts = accounts(uri);
        boolean exclude = "true".equalsIgnoreCase(uri.getQueryParameter("account_exclude"));
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            if (!Objects.equals(title, g.title) || g.dataSet != null) continue;
            if (!accounts.isEmpty() && in(accounts, g) == exclude) continue;
            out.add(g);
        }
        return out;
    }

    /** Each group's members (contact ids), with the list's has_phone_number / has_email parameters. */
    private static Map<Long, Set<Long>> memberships(ContentResolver r, Uri uri) {
        Map<Long, Set<Long>> out = new HashMap<>();
        String where = and("mimetype='" + MEMBERSHIP + "' AND contact_id IS NOT NULL", contactFilter(uri, "contact_id"));
        try (Cursor c = r.query(DATA, new String[]{"data1", "contact_id"}, where, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.isNull(0)) continue;
                long group;
                try {
                    group = Long.parseLong(c.getString(0));
                } catch (NumberFormatException e) {
                    continue;
                }
                Set<Long> members = out.get(group);
                if (members == null) out.put(group, members = new HashSet<>());
                members.add(c.getLong(1));
            }
        }
        return out;
    }

    private static Set<Long> of(Map<Long, Set<Long>> members, long group) {
        Set<Long> s = members.get(group);
        return s == null ? new HashSet<>() : s;
    }

    private static Cursor list(ContentResolver r, Uri uri, String kind, String[] projection, String selection, String[] args,
                               String sort) {
        List<Group> groups = groups(r);
        List<Object[]> rows = new ArrayList<>();
        String order;
        switch (kind) {
            case "assigned": {
                Map<Long, Set<Long>> members = memberships(r, uri);
                for (Group g : groups) {
                    rows.add(row(g.id, g.title, of(members, g.id).size(), g.systemId, g.accountName, g.accountType, g.dataSet,
                            g.autoAdd, g.autoAdd, g.favorites, g.shouldSync, g.visible,
                            "vnd.sec.contact.phone".equals(g.accountType) ? 0 : 1, "0", 0, 0));
                }
                order = "groups_type, account_type, account_name, data_set, title COLLATE LOCALIZED ASC";
                break;
            }
            case "aggregated": {
                // One row per title across accounts; a My Contacts-like title can leave some accounts' members out.
                Map<Long, Set<Long>> members = memberships(r, uri);
                boolean exclude = "true".equalsIgnoreCase(uri.getQueryParameter("account_exclude"));
                String excludedTitle = uri.getQueryParameter("title");
                List<String[]> excluded = exclude ? accounts(uri) : new ArrayList<>();
                Map<String, List<Group>> byTitle = new LinkedHashMap<>();
                for (Group g : groups) {
                    if (g.dataSet != null || g.title == null) continue;
                    List<Group> same = byTitle.get(g.title);
                    if (same == null) byTitle.put(g.title, same = new ArrayList<>());
                    same.add(g);
                }
                for (List<Group> same : byTitle.values()) {
                    Set<Long> contacts = new HashSet<>();
                    long id = Long.MAX_VALUE, autoAdd = Long.MIN_VALUE, reverseAutoAdd = Long.MAX_VALUE;
                    String systemId = null;
                    for (Group g : same) {
                        id = Math.min(id, g.id);
                        autoAdd = Math.max(autoAdd, g.autoAdd);
                        reverseAutoAdd = Math.min(reverseAutoAdd, g.autoAdd);
                        if (g.systemId != null && (systemId == null || g.systemId.compareTo(systemId) > 0)) systemId = g.systemId;
                        if (g.title.equals(excludedTitle) && in(excluded, g)) continue;
                        contacts.addAll(of(members, g.id));
                    }
                    Group first = same.get(0);
                    rows.add(row(id, first.title, contacts.size(), systemId, AGG_NAME, AGG_TYPE, null, autoAdd, reverseAutoAdd,
                            first.favorites, first.shouldSync, first.visible, 1, null, 0, 0));
                }
                order = "title COLLATE LOCALIZED ASC";
                break;
            }
            case "not_assigned": {
                String where = and("_id NOT IN (SELECT contact_id FROM view_data WHERE mimetype='" + MEMBERSHIP
                        + "' AND contact_id IS NOT NULL AND data1 IN (" + join(assigning(groups)) + "))", contactFilter(uri, "_id"));
                int count = 0;
                try (Cursor c = r.query(CONTACTS, new String[]{"_id"}, where, null, null)) {
                    if (c != null) count = c.getCount();
                }
                rows.add(row(0L, NOT_ASSIGNED, count, NOT_ASSIGNED, "", "", "", 0, 0, 0, 1, 1, 0, null, 0, 0));
                order = null;
                break;
            }
            default:
                return null;
        }
        MatrixCursor out = evaluate(rows, projection, selection, args, sort != null ? sort : order);
        if (out != null) out.setNotificationUri(r, Uri.parse("content://com.android.contacts"));
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "groups " + uri + " where " + selection + " -> " + (out == null ? "null" : out.getCount()));
        }
        return out == null ? null : ProviderCalls.shape(out, projection);
    }

    private static Object[] row(Object... values) {
        return values;
    }

    /** The app's selection and sort order run on the list's rows (an in-memory table). */
    private static MatrixCursor evaluate(List<Object[]> rows, String[] projection, String selection, String[] args, String sort) {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            StringBuilder create = new StringBuilder("CREATE TABLE groups_list (");
            for (int i = 0; i < LIST_COLUMNS.length; i++) {
                if (i > 0) create.append(',');
                create.append(LIST_COLUMNS[i]);
            }
            db.execSQL(create.append(')').toString());
            for (Object[] row : rows) {
                ContentValues v = new ContentValues();
                for (int i = 0; i < LIST_COLUMNS.length; i++) put(v, LIST_COLUMNS[i], row[i]);
                db.insert("groups_list", null, v);
            }
            Set<String> missing = new HashSet<>();
            for (int attempt = 0; attempt < 8; attempt++) {
                try (Cursor c = db.query("groups_list", ProviderCalls.keep(projection, missing),
                        ProviderCalls.rewrite(selection, missing), args, null, null, ProviderCalls.rewrite(sort, missing))) {
                    return copy(c);
                } catch (SQLiteException | IllegalArgumentException e) {
                    String column = ProviderCalls.unknownColumn(e);
                    if (column == null || !missing.add(column)) {
                        Log.w(TAG, "groups list refused: " + selection + ": " + e.getMessage());
                        return null;
                    }
                }
            }
            return null;
        } finally {
            db.close();
        }
    }

    private static void put(ContentValues v, String key, Object value) {
        if (value == null) v.putNull(key);
        else if (value instanceof Long) v.put(key, (Long) value);
        else if (value instanceof Integer) v.put(key, (Integer) value);
        else v.put(key, value.toString());
    }

    private static MatrixCursor copy(Cursor c) {
        String[] columns = c.getColumnNames();
        MatrixCursor out = new MatrixCursor(columns, c.getCount());
        while (c.moveToNext()) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row[i] = c.getLong(i);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row[i] = c.getDouble(i);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        row[i] = c.getBlob(i);
                        break;
                    default:
                        row[i] = c.getString(i);
                }
            }
            out.addRow(row);
        }
        return out;
    }

    // Memberships by group title

    /**
     * Samsung's membership insert: data/group_membership puts a contact (each of its raw contacts) in the group of that
     * title in the raw contact's own account (Samsung's merged account) or in the given group of the given account;
     * data/group_membership_only_raw_contact_id puts one raw contact in the group of that title in the given account.
     * A missing group is made with the title in that account (never the emergency contacts' ICE). Answers the last
     * membership, as Samsung's provider.
     */
    static Uri insertMembership(ContentResolver r, Uri uri, ContentValues values) {
        String title = values.getAsString("title");
        String type = values.getAsString("account_type");
        String name = values.getAsString("account_name");
        boolean byRawContact = "group_membership_only_raw_contact_id".equals(uri.getLastPathSegment());
        long last = 0;
        try {
            if (title == null || title.isEmpty()) throw new IllegalStateException("no group title");
            // Emergency contacts are Samsung's own table, not a group: no ICE group is made in Android's provider.
            if (ICE.equals(title)) throw new IllegalStateException("emergency contacts are not a group here");
            Long key = values.getAsLong(byRawContact ? "raw_contact_id" : "contact_id");
            if (key == null) throw new IllegalStateException("no contact");
            String where = byRawContact ? "_id=" + key : "contact_id=" + key + " AND deleted=0";
            try (Cursor c = r.query(RAW_CONTACTS, new String[]{"_id", "account_name", "account_type", "data_set"}, where, null, null)) {
                while (c != null && c.moveToNext()) {
                    long raw = c.getLong(0);
                    String rawName = c.getString(1), rawType = c.getString(2), rawDataSet = c.getString(3);
                    if (rawType != null && SIM.contains(rawType)) continue;
                    long group;
                    if (byRawContact) {
                        group = group(r, title, name, type, null);
                    } else if (AGG_TYPE.equals(type)) {
                        group = group(r, title, rawName, rawType, rawDataSet);
                    } else {
                        Long given = values.getAsLong("data1");
                        if (given == null || !Objects.equals(name, rawName) || !Objects.equals(type, rawType)) continue;
                        group = given;
                    }
                    if (group <= 0) continue;
                    last = member(r, raw, group);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "group membership refused: " + values + ": " + e);
        }
        return ContentUris.withAppendedId(uri, last);
    }

    /** The group with this title in this account, made if the account takes groups; 0 when there is none. */
    private static long group(ContentResolver r, String title, String accountName, String accountType, String dataSet) {
        if ("preload".equals(dataSet)) dataSet = null;
        List<String> args = new ArrayList<>();
        args.add(title);
        String where = "deleted=0 AND title=? AND " + is("account_name", accountName, args) + " AND "
                + is("account_type", accountType, args) + " AND " + is("data_set", dataSet, args);
        try (Cursor c = r.query(GROUPS, new String[]{"_id"}, where, args.toArray(new String[0]), "system_id DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        if (accountType != null && !GROUP_ACCOUNTS.contains(accountType)) return 0;
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("group_visible", 1);
        if (accountName != null) v.put("account_name", accountName);
        if (accountType != null) v.put("account_type", accountType);
        if (dataSet != null) v.put("data_set", dataSet);
        Uri made = r.insert(GROUPS, v);
        return made == null ? 0 : ContentUris.parseId(made);
    }

    private static String is(String column, String value, List<String> args) {
        if (value == null) return column + " IS NULL";
        args.add(value);
        return column + "=?";
    }

    /** Puts the raw contact in the group (once); its membership row id. */
    private static long member(ContentResolver r, long raw, long group) {
        String where = "raw_contact_id=" + raw + " AND mimetype='" + MEMBERSHIP + "' AND data1=" + group;
        try (Cursor c = r.query(DATA, new String[]{"_id"}, where, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        ContentValues v = new ContentValues();
        v.put("raw_contact_id", raw);
        v.put("mimetype", MEMBERSHIP);
        v.put("data1", group);
        Uri made = r.insert(DATA, v);
        return made == null ? 0 : ContentUris.parseId(made);
    }
}
