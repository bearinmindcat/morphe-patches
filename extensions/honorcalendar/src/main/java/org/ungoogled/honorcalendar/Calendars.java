package org.ungoogled.honorcalendar;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calendar talks to "com.android.calendar" as if it were MagicOS's calendar provider, which is Android's with a few
 * additions: columns Android's lacks (sync_data2 of an instance, ...) and a phone calendar (account "Phone" of type
 * com.android.hihonor.phone) made when the database is and kept although no such account exists. Off MagicOS the
 * provider there is Android's own, which refuses the columns ("Invalid column sync_data2"), starts with no calendar and
 * would delete a calendar of an account the phone lacks; only its LOCAL account type is kept. The patch sends
 * Calendar's ContentResolver and ContentProviderClient calls here: other providers are passed straight through; for
 * the calendar provider the columns are fitted to Android's, and the phone calendar is stored as a LOCAL one, the app
 * seeing it under MagicOS's account type.
 */
public final class Calendars {
    private static final String TAG = "UA";
    static final String AUTHORITY = CalendarContract.AUTHORITY;
    static final Uri BASE = Uri.parse("content://" + AUTHORITY);

    /** MagicOS's phone calendar ("Phone" in the app), which its provider makes with the database. */
    static final String LOCAL_NAME = "Phone";
    static final String LOCAL_DISPLAY = "Phone";
    /** Its account type on MagicOS (the app's), and as stored here: Android keeps only LOCAL accounts without an account. */
    static final String PHONE_TYPE = "com.android.hihonor.phone";
    static final String LOCAL_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL;

    /** The URI parameters Android's calendar provider takes (CalendarProvider2.ALLOWED_URI_PARAMETERS). */
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            CalendarContract.CALLER_IS_SYNCADAPTER, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE));

    /** Paths to ask for a table's columns (all of them: no projection, no rows). */
    private static final Map<String, String> SCHEMA_PATH = new HashMap<>();

    static {
        for (String kind : new String[]{"events", "calendars", "attendees", "reminders", "extendedproperties",
                "calendar_alerts", "colors"}) {
            SCHEMA_PATH.put(kind, kind);
        }
        SCHEMA_PATH.put("event_entities", "events");
        SCHEMA_PATH.put("calendar_entities", "calendars");
        SCHEMA_PATH.put("instances", "instances/when/0/1");
    }

    /** Events' own columns that Calendars also has; every other Calendars column comes from the join. */
    private static final Set<String> SHARED_WITH_CALENDARS = new HashSet<>(Arrays.asList(
            "_id", "_sync_id", "dirty", "mutators", "deleted"));

    private static final Pattern INVALID_COLUMN = Pattern.compile("(?:Invalid column |no such column: |has no column named )\"?([A-Za-z_][A-Za-z0-9_.]*)");

    private static final ConcurrentHashMap<String, Set<String>> SCHEMAS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> MISSING = new ConcurrentHashMap<>();
    private static volatile boolean localCalendarChecked;

    private Calendars() {
    }

    static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    @SuppressLint("PrivateApi")
    static Context context() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ContentResolver resolver() {
        return context().getContentResolver();
    }

    private static boolean isCalendar(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority()) && "content".equals(uri.getScheme());
    }

    private static String kind(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? "" : segments.get(0);
    }

    // URI parameters.

    /**
     * The URI with only the parameters Android's provider takes ("limit" is handed back for the sort order), the phone
     * calendar's account type as stored.
     */
    private static Uri fit(Uri uri) {
        Set<String> names = uri.getQueryParameterNames();
        if (ALLOWED.containsAll(names) && !PHONE_TYPE.equals(uri.getQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE))) {
            return uri;
        }
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : names) {
            if (!ALLOWED.contains(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, stored(value));
        }
        return b.build();
    }

    // The phone calendar's account type: MagicOS's for the app, LOCAL in Android's provider.

    private static String stored(String value) {
        return PHONE_TYPE.equals(value) ? LOCAL_TYPE : value;
    }

    private static String[] stored(String[] args) {
        if (args == null) return null;
        String[] out = null;
        for (int i = 0; i < args.length; i++) {
            if (PHONE_TYPE.equals(args[i])) {
                if (out == null) out = args.clone();
                out[i] = LOCAL_TYPE;
            }
        }
        return out != null ? out : args;
    }

    /** SQL naming the phone account type as a string literal. */
    private static String storedSql(String sql) {
        return sql == null ? null : sql.replace("'" + PHONE_TYPE + "'", "'" + LOCAL_TYPE + "'")
                .replace("\"" + PHONE_TYPE + "\"", "'" + LOCAL_TYPE + "'");
    }

    private static ContentValues stored(ContentValues values) {
        if (values == null) return null;
        ContentValues out = null;
        for (String key : values.keySet()) {
            Object value = values.get(key);
            if (PHONE_TYPE.equals(value)) {
                if (out == null) out = new ContentValues(values);
                out.put(key, LOCAL_TYPE);
            }
        }
        return out != null ? out : values;
    }

    /** A cursor of the calendar provider as the app reads it: the phone calendar's account type as MagicOS has it. */
    private static Cursor shown(Cursor c) {
        if (c == null) return null;
        for (String name : c.getColumnNames()) {
            if (name.toLowerCase(Locale.ROOT).endsWith("account_type")) return new PhoneAccount(c);
        }
        return c;
    }

    private static String sortWithLimit(Uri uri, String sort) {
        String limit = uri.getQueryParameter("limit");
        if (limit == null || !limit.matches("\\d+")) return sort;
        return (sort == null ? "_id" : sort) + " LIMIT " + limit;
    }

    private static Uri writeUri(ContentResolver r, Uri uri, ContentValues values) {
        return fit(uri);
    }

    // Columns.

    private static Set<String> missing(String kind) {
        Set<String> set = MISSING.get(kind);
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            Set<String> before = MISSING.putIfAbsent(kind, set);
            if (before != null) set = before;
        }
        return set;
    }

    /** Android's columns for a kind of path as a query lists them (lower case), or null where they cannot be listed. */
    private static Set<String> listing(ContentResolver r, String kind) {
        Set<String> known = SCHEMAS.get(kind);
        if (known != null) return known.isEmpty() ? null : known;
        known = new HashSet<>();
        String path = SCHEMA_PATH.get(kind);
        if (path != null) {
            try (Cursor c = r.query(Uri.withAppendedPath(BASE, path), null, "0", null, null)) {
                if (c != null) {
                    for (String name : c.getColumnNames()) known.add(name.toLowerCase(Locale.ROOT));
                }
            } catch (RuntimeException e) {
                if (debug()) Log.d(TAG, "calendar columns of " + kind + ": " + e);
            }
        }
        SCHEMAS.put(kind, known);
        return known.isEmpty() ? null : known;
    }

    /** The columns a row of a kind of path stores, or null where they cannot be listed. */
    private static Set<String> schema(ContentResolver r, String kind) {
        Set<String> listed = listing(r, kind);
        if (listed == null || !("events".equals(kind) || "event_entities".equals(kind))) return listed;
        Set<String> known = SCHEMAS.get("stored:" + kind);
        if (known != null) return known;
        // An event's listing also carries its calendar's columns, which the event row does not store.
        known = new HashSet<>(listed);
        Set<String> calendars = listing(r, "calendars");
        if (calendars != null) {
            for (String column : calendars) {
                if (!SHARED_WITH_CALENDARS.contains(column)) known.remove(column);
            }
        }
        known.remove("displaycolor");
        SCHEMAS.put("stored:" + kind, known);
        return known;
    }

    private static boolean plainColumn(String s) {
        return s.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    }

    private static String bare(String column) {
        int dot = column.lastIndexOf('.');
        return (dot < 0 ? column : column.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }

    /** The projection without what Android's provider lacks. */
    private static String[] keep(ContentResolver r, String kind, String[] projection) {
        Set<String> listed = listing(r, kind);
        Set<String> gone = missing(kind);
        List<String> out = new ArrayList<>(projection.length);
        for (String column : projection) {
            if (column == null) continue;
            if (gone.contains(column.toLowerCase(Locale.ROOT))) continue;
            if (listed != null && plainColumn(column) && !listed.contains(bare(column))) {
                gone.add(bare(column));
                if (debug()) Log.d(TAG, "calendar column missing in " + kind + ": " + column);
                continue;
            }
            out.add(column);
        }
        return out.toArray(new String[0]);
    }

    /**
     * What a column only MagicOS's provider has reads as in SQL, where NULL would not do: an alert's conflict_tag
     * (MagicOS's conflict handling; 0 = none, which the reminder query asks for). Typed, so "= '0'" holds too.
     */
    private static final Map<String, String> SQL_DEFAULTS = new HashMap<>();

    static {
        SQL_DEFAULTS.put("conflict_tag", "CAST(0 AS INTEGER)");
    }

    /** SQL with every column Android's provider lacks replaced by NULL (or its SQL_DEFAULTS value). */
    static String rewrite(String sql, Set<String> gone) {
        if (sql == null || gone.isEmpty()) return sql;
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == '\'') {
                        if (j + 1 < n && sql.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                out.append(sql, i, Math.min(n, j + 1));
                i = j + 1;
            } else if (Character.isLetter(ch) || ch == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '.')) j++;
                String word = sql.substring(i, j);
                boolean missing = gone.contains(bare(word)) && (j >= n || sql.charAt(j) != '(');
                out.append(missing ? (SQL_DEFAULTS.containsKey(bare(word)) ? SQL_DEFAULTS.get(bare(word)) : "NULL") : word);
                i = j;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /** Learns the column Android's provider just refused; false when the error is about something else. */
    private static boolean learn(String kind, RuntimeException e) {
        Matcher m = INVALID_COLUMN.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) return false;
        String column = bare(m.group(1));
        if (!missing(kind).add(column)) return false;
        if (debug()) Log.d(TAG, "calendar column missing in " + kind + ": " + column);
        return true;
    }

    // Queries.

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort) {
        return query(r, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) {
        if (!isCalendar(uri)) return r.query(uri, projection, selection, args, sort, signal);
        String kind = kind(uri);
        if ("calendars".equals(kind)) ensureLocalCalendar(r);
        sort = sortWithLimit(uri, sort);
        Uri fitted = fit(uri);
        RuntimeException last = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            Set<String> gone = missing(kind);
            String[] p = projection == null ? null : keep(r, kind, projection);
            try {
                Cursor c = r.query(fitted, p, storedSql(rewrite(selection, gone)), stored(args), rewrite(sort, gone), signal);
                if (c == null || projection == null || p.length == projection.length) return shown(c);
                return shown(new Columns(c, projection));
            } catch (IllegalArgumentException | SQLiteException e) {
                last = e;
                if (!learn(kind, e)) throw e;
            }
        }
        throw last;
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        if (!isCalendar(uri) || queryArgs == null) {
            return isCalendar(uri) ? query(r, uri, projection, null, null, null, signal) : r.query(uri, projection, queryArgs, signal);
        }
        String selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
        String[] args = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
        String sort = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
        String limit = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_LIMIT);
        if (limit == null && queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT)) {
            limit = String.valueOf(queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT));
        }
        if (limit != null) sort = (sort == null ? "_id" : sort) + " LIMIT " + limit;
        return query(r, uri, projection, selection, args, sort, signal);
    }

    public static Cursor query(ContentProviderClient client, Uri uri, String[] projection, String selection, String[] args,
                               String sort) throws RemoteException {
        if (!isCalendar(uri)) return client.query(uri, projection, selection, args, sort);
        return query(resolver(), uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentProviderClient client, Uri uri, String[] projection, String selection, String[] args,
                               String sort, CancellationSignal signal) throws RemoteException {
        if (!isCalendar(uri)) return client.query(uri, projection, selection, args, sort, signal);
        return query(resolver(), uri, projection, selection, args, sort, signal);
    }

    public static Cursor query(ContentProviderClient client, Uri uri, String[] projection, Bundle queryArgs,
                               CancellationSignal signal) throws RemoteException {
        if (!isCalendar(uri)) return client.query(uri, projection, queryArgs, signal);
        return query(resolver(), uri, projection, queryArgs, signal);
    }

    /** CalendarContract.EventDays.query, through the fitting above. */
    public static Cursor query(ContentResolver r, int startDay, int numDays, String[] projection) {
        if (numDays < 1) return null;
        Uri.Builder b = CalendarContract.EventDays.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, startDay);
        android.content.ContentUris.appendId(b, startDay + numDays - 1);
        return query(r, b.build(), projection, "selected=1", null, CalendarContract.EventDays.STARTDAY);
    }

    // Writes.

    /**
     * The values without the columns Android's table lacks. Only an event's columns are known up front (its listing is
     * the table's); for the other tables a query does not list every stored column (calendar_alerts' receivedTime),
     * so their missing columns are only learnt from the provider's refusals.
     */
    private static ContentValues strip(ContentResolver r, String kind, ContentValues values) {
        if (values == null) return null;
        Set<String> known = "events".equals(kind) || "event_entities".equals(kind) ? schema(r, kind) : null;
        Set<String> gone = missing(kind);
        ContentValues out = null;
        for (String key : values.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (gone.contains(k) || (known != null && !known.contains(k))) {
                if (out == null) out = new ContentValues(values);
                out.remove(key);
            }
        }
        if (out != null && debug()) Log.d(TAG, "calendar " + kind + " write without " + new ArrayList<>(values.keySet()) + " -> " + new ArrayList<>(out.keySet()));
        return out != null ? out : values;
    }

    /** Drops a value Android's provider refuses to take ("Only the provider / sync adapters may write to ..."). */
    private static boolean refuse(ContentValues values, RuntimeException e) {
        if (values == null) return false;
        String message = String.valueOf(e.getMessage());
        for (String prefix : new String[]{"Only the provider may write to ", "Only sync adapters may write to "}) {
            int at = message.indexOf(prefix);
            if (at < 0) continue;
            String key = message.substring(at + prefix.length()).trim();
            if (!values.containsKey(key)) return false;
            values.remove(key);
            if (debug()) Log.d(TAG, "calendar write without " + key + " (" + message + ")");
            return true;
        }
        return false;
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        if (!isCalendar(uri)) return r.insert(uri, values);
        String kind = kind(uri);
        Uri target = writeUri(r, uri, values);
        ContentValues v = values == null ? null : new ContentValues(strip(r, kind, stored(values)));
        for (int attempt = 0; attempt < 24; attempt++) {
            try {
                return r.insert(target, v);
            } catch (IllegalArgumentException | SQLiteException e) {
                if (refuse(v, e)) continue;
                if (!learn(kind, e)) throw e;
                v = new ContentValues(strip(r, kind, v));
            }
        }
        return null;
    }

    public static int update(ContentResolver r, Uri uri, ContentValues values, String selection, String[] args) {
        if (!isCalendar(uri)) return r.update(uri, values, selection, args);
        String kind = kind(uri);
        Uri target = writeUri(r, uri, values);
        ContentValues v = values == null ? null : new ContentValues(strip(r, kind, stored(values)));
        for (int attempt = 0; attempt < 24; attempt++) {
            if (v != null && v.size() == 0) return 0;
            try {
                return r.update(target, v, storedSql(rewrite(selection, missing(kind))), stored(args));
            } catch (IllegalArgumentException | SQLiteException e) {
                if (refuse(v, e)) continue;
                if (!learn(kind, e)) throw e;
                v = new ContentValues(strip(r, kind, v));
            }
        }
        return 0;
    }

    public static int delete(ContentResolver r, Uri uri, String selection, String[] args) {
        if (!isCalendar(uri)) return r.delete(uri, selection, args);
        String kind = kind(uri);
        Uri target = writeUri(r, uri, null);
        for (int attempt = 0; attempt < 24; attempt++) {
            try {
                return r.delete(target, storedSql(rewrite(selection, missing(kind))), stored(args));
            } catch (IllegalArgumentException | SQLiteException e) {
                if (!learn(kind, e)) throw e;
            }
        }
        return 0;
    }

    public static int bulkInsert(ContentResolver r, Uri uri, ContentValues[] values) {
        if (!isCalendar(uri)) return r.bulkInsert(uri, values);
        int n = 0;
        for (ContentValues v : values) {
            if (insert(r, uri, v) != null) n++;
        }
        return n;
    }

    /** Runs a batch one operation at a time through the calls above (Android's provider would refuse MagicOS's). */
    public static ContentProviderResult[] applyBatch(ContentResolver r, String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        if (!AUTHORITY.equals(authority)) return r.applyBatch(authority, operations);
        Through through = new Through(r);
        ContentProviderResult[] results = new ContentProviderResult[operations.size()];
        for (int i = 0; i < results.length; i++) {
            results[i] = operations.get(i).apply(through, results, i);
        }
        return results;
    }

    public static Bundle call(ContentResolver r, Uri uri, String method, String arg, Bundle extras) {
        if (!isCalendar(uri)) return r.call(uri, method, arg, extras);
        try {
            return r.call(fit(uri), method, arg, extras);
        } catch (RuntimeException e) {
            if (debug()) Log.d(TAG, "calendar call " + method + ": " + e);
            return null;
        }
    }

    public static Bundle call(ContentResolver r, String authority, String method, String arg, Bundle extras) {
        if (!AUTHORITY.equals(authority)) return r.call(authority, method, arg, extras);
        try {
            return r.call(authority, method, arg, extras);
        } catch (RuntimeException e) {
            if (debug()) Log.d(TAG, "calendar call " + method + ": " + e);
            return null;
        }
    }

    // The same through a ContentProviderClient.

    public static Uri insert(ContentProviderClient client, Uri uri, ContentValues values) throws RemoteException {
        return isCalendar(uri) ? insert(resolver(), uri, values) : client.insert(uri, values);
    }

    public static int update(ContentProviderClient client, Uri uri, ContentValues values, String selection, String[] args)
            throws RemoteException {
        return isCalendar(uri) ? update(resolver(), uri, values, selection, args) : client.update(uri, values, selection, args);
    }

    public static int delete(ContentProviderClient client, Uri uri, String selection, String[] args) throws RemoteException {
        return isCalendar(uri) ? delete(resolver(), uri, selection, args) : client.delete(uri, selection, args);
    }

    public static int bulkInsert(ContentProviderClient client, Uri uri, ContentValues[] values) throws RemoteException {
        return isCalendar(uri) ? bulkInsert(resolver(), uri, values) : client.bulkInsert(uri, values);
    }

    public static ContentProviderResult[] applyBatch(ContentProviderClient client, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        for (ContentProviderOperation op : operations) {
            if (!isCalendar(op.getUri())) return client.applyBatch(operations);
        }
        return applyBatch(resolver(), AUTHORITY, operations);
    }

    /** A provider object (never registered) for ContentProviderOperation.apply to call. */
    private static final class Through extends ContentProvider {
        private final ContentResolver r;

        Through(ContentResolver r) {
            this.r = r;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            return Calendars.query(r, uri, projection, selection, args, sort);
        }

        @Override
        public String getType(Uri uri) {
            return r.getType(uri);
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return Calendars.insert(r, uri, values);
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return Calendars.delete(r, uri, selection, args);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return Calendars.update(r, uri, values, selection, args);
        }
    }

    // MagicOS's provider starts with the phone calendar; Android's starts with none.

    private static void ensureLocalCalendar(ContentResolver r) {
        if (localCalendarChecked) return;
        synchronized (Calendars.class) {
            if (localCalendarChecked) return;
            try (Cursor c = r.query(CalendarContract.Calendars.CONTENT_URI, new String[]{"_id"},
                    "account_type=? AND account_name=?", new String[]{LOCAL_TYPE, LOCAL_NAME}, null)) {
                localCalendarChecked = true;
                if (c == null || c.getCount() > 0) return;
            } catch (RuntimeException e) {
                // No calendar permission yet: ask again next time.
                return;
            }
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_NAME);
            v.put(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_TYPE);
            v.put(CalendarContract.Calendars.NAME, LOCAL_DISPLAY);
            v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_DISPLAY);
            v.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF256FFF);
            v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
            v.put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_NAME);
            v.put(CalendarContract.Calendars.VISIBLE, 1);
            v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
            v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
            v.put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1,2,4");
            v.put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "0,1,2");
            v.put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "0,1,2");
            Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_TYPE)
                    .build();
            try {
                Uri made = r.insert(uri, v);
                if (debug()) Log.d(TAG, "phone calendar made: " + made);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not make the phone calendar: " + e);
            }
        }
    }

    /** The account type column(s) of a cursor with the phone calendar's (LOCAL, account "Phone") read as MagicOS's. */
    private static final class PhoneAccount extends android.database.CursorWrapper {
        private final boolean[] type;
        private final int name;

        PhoneAccount(Cursor inner) {
            super(inner);
            String[] names = inner.getColumnNames();
            type = new boolean[names.length];
            int n = -1;
            for (int i = 0; i < names.length; i++) {
                String lower = names[i].toLowerCase(Locale.ROOT);
                type[i] = lower.endsWith("account_type");
                if (lower.equals("account_name") || lower.endsWith(".account_name")) n = i;
            }
            name = n;
        }

        @Override
        public String getString(int column) {
            String value = super.getString(column);
            if (column >= 0 && column < type.length && type[column] && LOCAL_TYPE.equals(value)
                    && (name < 0 || LOCAL_NAME.equals(super.getString(name)))) {
                return PHONE_TYPE;
            }
            return value;
        }
    }
}
