package org.ungoogled.samsungcontacts;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's "Phone" storage is Samsung's account vnd.sec.contact.phone, which only Samsung's contacts provider keeps (it
 * is One UI's local account). Android's provider keeps only accounts an installed app owns, so off One UI every contact
 * and group saved to "Phone" was deleted the next time any account was added or removed. Off One UI "Phone" is
 * Android's own local storage (no account), as on One UI: writes for the phone account go there, the local account
 * reads as the phone account, the app's account conditions and account parameters match both, and raw contacts and
 * groups an earlier version saved under the phone account are moved to the local account in place (same rows: their
 * data, photos, group memberships, favourites and lookup keys stay).
 */
public final class LocalAccount {
    private static final String TAG = "UA";
    static final String PHONE = "vnd.sec.contact.phone";
    private static final String QUOTED = "'" + PHONE + "'";
    private static final Uri RAW_CONTACTS = Uri.parse("content://com.android.contacts/raw_contacts");
    private static final Uri GROUPS = Uri.parse("content://com.android.contacts/groups");
    private static final Uri SETTINGS = Uri.parse("content://com.android.contacts/settings");
    private static final Uri PROFILE_RAW_CONTACTS = Uri.parse("content://com.android.contacts/profile/raw_contacts");
    /** Rows saved under the phone account by an earlier version (not deleted ones: those are gone for the user). */
    private static final String SAVED_UNDER_PHONE = "account_type=" + QUOTED + " AND account_name=" + QUOTED
            + " AND data_set IS NULL AND deleted=0";
    private static final Pattern COLUMN = Pattern.compile(
            "(?<![A-Za-z0-9_.'\"])((?:[A-Za-z_][A-Za-z0-9_]*\\.)?)(account_type_and_data_set|account_type|account_name)(?![A-Za-z0-9_'\"])",
            Pattern.CASE_INSENSITIVE);

    /** Whether the phone is off One UI (Android's provider): null until known. */
    private static volatile Boolean active;
    private static volatile ContentResolver resolver;
    private static volatile boolean moved;
    private static boolean moving;
    private static long lastTry;
    /** The local account the phone's maker set up (Android 14 and newer), {name, type}, or nulls. */
    private static String[] configured;

    private LocalAccount() {
    }

    /** Called before each contacts provider call: notes the app's resolver and moves old phone-account rows once. */
    static void prepare(ContentResolver r) {
        if (r == null) return;
        resolver = r;
        if (active == null) active(r);
        if (!moved && Boolean.TRUE.equals(active)) start(r);
    }

    /**
     * Starts the move on its own thread (many contacts take a while, and the first call can come from the main thread);
     * until it is done the old rows read as the phone account anyway.
     */
    private static void start(ContentResolver r) {
        synchronized (LocalAccount.class) {
            long now = System.currentTimeMillis();
            if (moved || moving || now - lastTry < 10_000) return;
            lastTry = now;
            moving = true;
        }
        new Thread(() -> {
            try {
                move(r);
            } finally {
                synchronized (LocalAccount.class) {
                    moving = false;
                }
            }
        }, "PhoneStorage").start();
    }

    private static long lastCheck;

    static ContentResolver resolver() {
        return resolver;
    }

    /** Whether the phone account is mapped: Samsung's own provider (sec_ columns) keeps it as its local account. */
    static boolean active() {
        Boolean a = active;
        return a == null || a;
    }

    private static synchronized void active(ContentResolver r) {
        long now = System.currentTimeMillis();
        if (active != null || now - lastCheck < 10_000) return;
        lastCheck = now;
        try (Cursor c = r.query(RAW_CONTACTS, null, "0", null, null)) {
            if (c == null) return;
            boolean samsung = false;
            for (String name : c.getColumnNames()) {
                if (name.startsWith("sec_")) {
                    samsung = true;
                    break;
                }
            }
            active = !samsung;
        } catch (RuntimeException e) {
            // No contacts permission yet: asked again on the next call.
        }
    }

    private static String[] configured() {
        String[] c = configured;
        if (c != null) return c;
        c = new String[2];
        try {
            Resources system = Resources.getSystem();
            int name = system.getIdentifier("config_rawContactsLocalAccountName", "string", "android");
            int type = system.getIdentifier("config_rawContactsLocalAccountType", "string", "android");
            if (name != 0 && type != 0) {
                c[0] = emptyToNull(system.getString(name));
                c[1] = emptyToNull(system.getString(type));
            }
        } catch (RuntimeException e) {
            // No such setting (Android 13): the local account is the one without an account.
        }
        if (c[0] == null || c[1] == null) c[0] = c[1] = null;
        configured = c;
        return c;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Whether an account (as stored) is the phone's local storage. */
    static boolean isLocal(String name, String type) {
        if (name == null && type == null) return true;
        String[] c = configured();
        return c[1] != null && c[1].equals(type) && c[0].equals(name);
    }

    // The local account as the app's phone account (Android's ContactsContract.RawContacts getters, Android 14 and newer)

    public static String getLocalAccountName(Context context) {
        return PHONE;
    }

    public static String getLocalAccountType(Context context) {
        return PHONE;
    }

    // Writes

    /** Values naming the phone account (without a data set) name the local account instead. */
    static ContentValues values(ContentValues values) {
        if (values == null || !active() || !PHONE.equals(values.getAsString("account_type"))) return values;
        String name = values.getAsString("account_name");
        if (name != null && !PHONE.equals(name)) return values;
        if (values.getAsString("data_set") != null) return values;
        ContentValues out = new ContentValues(values);
        out.putNull("account_name");
        out.putNull("account_type");
        if (out.containsKey("data_set")) out.putNull("data_set");
        return out;
    }

    // Account parameters

    /** Whether a URI names the phone account (without a data set) in account parameters. */
    private static boolean namesPhone(Uri uri) {
        if (!uri.isHierarchical()) return false;
        return PHONE.equals(uri.getQueryParameter("account_type")) && PHONE.equals(uri.getQueryParameter("account_name"))
                && emptyToNull(uri.getQueryParameter("data_set")) == null;
    }

    /** 1: rows are contacts; 2: rows carry their account; 0: a path whose account parameters are left as they are. */
    private static int rows(Uri uri) {
        List<String> p = uri.getPathSegments();
        if (p.isEmpty()) return 0;
        String first = p.get(0);
        if ("profile".equals(first)) {
            if (p.size() == 1) return 1;
            first = p.get(1);
            return "raw_contacts".equals(first) || "data".equals(first) || "raw_contact_entities".equals(first)
                    || "entities".equals(first) ? 2 : 0;
        }
        switch (first) {
            case "contacts":
                if (p.size() == 1) return 1;
                switch (p.get(1)) {
                    case "filter":
                    case "strequent":
                    case "frequent":
                    case "group":
                        return 1;
                }
                return 0;
            case "raw_contacts":
            case "data":
            case "groups":
            case "settings":
            case "raw_contact_entities":
                return 2;
            default:
                return 0;
        }
    }

    /**
     * The URI without phone account parameters: Android's provider can only filter by an account that has a name, so
     * the condition goes into the selection instead (see {@link #clause}).
     */
    static Uri uri(Uri uri) {
        if (!active() || !namesPhone(uri) || rows(uri) == 0) return uri;
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("account_name".equals(name) || "account_type".equals(name) || "data_set".equals(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        return b.build();
    }

    /** The phone account parameters of a URI as a condition (in the app's terms: see {@link #selection}), or null. */
    static String clause(Uri uri) {
        if (!active() || !namesPhone(uri)) return null;
        String account = "account_type=" + QUOTED + " AND account_name=" + QUOTED + " AND data_set IS NULL";
        switch (rows(uri)) {
            case 1:
                return "_id IN (SELECT contact_id FROM view_raw_contacts WHERE " + account + ")";
            case 2:
                return account;
            default:
                return null;
        }
    }

    // Selections and sort orders

    /**
     * Account columns in a selection or sort order read the local account as the phone account, so the app's
     * conditions on its phone account (literal or argument) match local contacts (and old phone-account ones).
     * Quoted text is left alone; columns the provider lacks are left to ProviderCalls.
     */
    static String selection(String sql, Set<String> missing) {
        if (sql == null || !active()) return sql;
        String lower = sql.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("account_type") && !lower.contains("account_name")) return sql;
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                int end = i + 1;
                while (end < n) {
                    if (sql.charAt(end) == ch) {
                        if (end + 1 < n && sql.charAt(end + 1) == ch) {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                end = Math.min(end + 1, n);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            int next = i;
            while (next < n && sql.charAt(next) != '\'' && sql.charAt(next) != '"') next++;
            out.append(columns(sql.substring(i, next), missing));
            i = next;
        }
        return out.toString();
    }

    private static String columns(String part, Set<String> missing) {
        Matcher m = COLUMN.matcher(part);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String table = m.group(1);
            String column = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (missing != null && missing.contains(column)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(expression(table, column)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String expression(String table, String column) {
        String[] c = configured();
        String value = table + column;
        if (c[1] == null) return "IFNULL(" + value + "," + QUOTED + ")";
        // The maker's local account (a name and a type) is local too.
        String type = "account_name".equals(column) ? table + "account_type" : value;
        return "(CASE WHEN " + type + " IS NULL OR " + type + "=" + android.database.DatabaseUtils.sqlEscapeString(c[1])
                + " THEN " + QUOTED + " ELSE " + value + " END)";
    }

    // Reads

    /** A cursor whose account columns read the local account as the phone account. */
    static Cursor cursor(Cursor c) {
        if (c == null || !active()) return c;
        int type = c.getColumnIndex("account_type");
        int name = c.getColumnIndex("account_name");
        int typeAndDataSet = c.getColumnIndex("account_type_and_data_set");
        if (type < 0 && name < 0 && typeAndDataSet < 0) return c;
        return new Mapped(c, type, name, typeAndDataSet);
    }

    private static final class Mapped extends CursorWrapper {
        private final int type, name, typeAndDataSet;

        Mapped(Cursor c, int type, int name, int typeAndDataSet) {
            super(c);
            this.type = type;
            this.name = name;
            this.typeAndDataSet = typeAndDataSet;
        }

        /** Whether this row's account is the local one (from the columns the query asked for). */
        private boolean local() {
            Cursor c = getWrappedCursor();
            String[] local = configured();
            if (type >= 0) {
                String t = c.getString(type);
                if (name >= 0) return isLocal(c.getString(name), t);
                return t == null || t.equals(local[1]);
            }
            if (name >= 0) return c.getString(name) == null;
            String t = c.getString(typeAndDataSet);
            return t == null || t.equals(local[1]);
        }

        private boolean mapped(int column) {
            return column >= 0 && (column == type || column == name || column == typeAndDataSet) && local();
        }

        @Override
        public String getString(int column) {
            return mapped(column) ? PHONE : super.getString(column);
        }

        @Override
        public boolean isNull(int column) {
            return !mapped(column) && super.isNull(column);
        }

        @Override
        public int getType(int column) {
            return mapped(column) ? FIELD_TYPE_STRING : super.getType(column);
        }

        @Override
        public void copyStringToBuffer(int column, android.database.CharArrayBuffer buffer) {
            if (!mapped(column)) {
                super.copyStringToBuffer(column, buffer);
                return;
            }
            char[] chars = PHONE.toCharArray();
            if (buffer.data == null || buffer.data.length < chars.length) buffer.data = chars;
            else System.arraycopy(chars, 0, buffer.data, 0, chars.length);
            buffer.sizeCopied = chars.length;
        }
    }

    // Contacts an earlier version saved under the phone account

    /**
     * Moves raw contacts and groups saved under the phone account to the local account: one update each, in one batch
     * (one transaction), so the rows keep their ids and with them their data, photos, memberships and favourites; the
     * lookup keys stay too (Android's account hash is 0 for both accounts). Nothing is deleted. Runs once per process,
     * again later when the contacts permission was missing.
     */
    private static void move(ContentResolver r) {
        try {
            int raws = count(r, RAW_CONTACTS);
            int groups = count(r, GROUPS);
            if (raws > 0 || groups > 0) {
                ContentValues local = new ContentValues();
                local.putNull("account_name");
                local.putNull("account_type");
                ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                if (groups > 0) ops.add(ContentProviderOperation.newUpdate(GROUPS).withValues(local).withSelection(SAVED_UNDER_PHONE, null).build());
                if (raws > 0) ops.add(ContentProviderOperation.newUpdate(RAW_CONTACTS).withValues(local).withSelection(SAVED_UNDER_PHONE, null).build());
                // Changes no row, but makes the provider work out again which contacts are visible (the local
                // account's settings) when the batch commits; otherwise that waited for the next account change.
                ops.add(ContentProviderOperation.newUpdate(SETTINGS).withValue("ungrouped_visible", 1).withSelection("0", null).build());
                r.applyBatch("com.android.contacts", ops);
                Log.i(TAG, "phone storage: moved " + raws + " contacts and " + groups + " groups to the phone's local storage");
            }
            int profile = count(r, PROFILE_RAW_CONTACTS);
            if (profile > 0) {
                ContentValues local = new ContentValues();
                local.putNull("account_name");
                local.putNull("account_type");
                r.update(PROFILE_RAW_CONTACTS, local, SAVED_UNDER_PHONE, null);
                Log.i(TAG, "phone storage: moved the profile to the phone's local storage");
            }
            moved = true;
        } catch (SecurityException e) {
            // No contacts permission yet: tried again later.
        } catch (Exception e) {
            // Tried again when the app next starts.
            moved = true;
            Log.w(TAG, "phone storage: not moved: " + e);
        }
    }

    private static int count(ContentResolver r, Uri uri) {
        try (Cursor c = r.query(uri, new String[]{"_id"}, SAVED_UNDER_PHONE, null, null)) {
            return c == null ? 0 : c.getCount();
        }
    }
}
