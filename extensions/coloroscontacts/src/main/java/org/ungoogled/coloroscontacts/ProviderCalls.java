package org.ungoogled.coloroscontacts;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OnePlus Contacts reads and writes Android's contacts provider and call log as if they were ColorOS's, which have more
 * columns (ring_time, simid, oplus_feature, account_type on contacts, ...) and paths (the call history as
 * calls_with_normalized / color_calls_customized / customize_calls). Off ColorOS an unknown column fails the query or
 * write ("Invalid column ...", "no such column") and an unknown path throws ("Unknown URL"). The patch sends the app's
 * ContentResolver and ContentProviderClient calls here: other providers are passed on (a resolver's to ColorOS, where
 * refusals by OPPO's apps read as their absence); for the contacts provider, the call log and the telephony providers
 * ColorOS's call history paths become Android's call log, a column they lack is learnt from the error (or, for the call
 * log, from its column list), left out of the call (writes), read as its default (queries) or replaced by it
 * (selections, sort orders), and a refused call log (no Call logs permission, or the voicemail rows only the phone app
 * may read) reads as an empty one. ColorOS's own searches (dial pad contacts, pinyin) have no Android counterpart and
 * stay unknown: they find nothing.
 */
public final class ProviderCalls {
    private static final String TAG = "UA";
    static final Object DEFAULT = null;
    private static final Pattern NO_COLUMN = Pattern.compile("(?:no such column: |has no column named |Invalid column |Invalid token )([A-Za-z0-9_.]+)");

    /** Columns the provider lacks, per authority and table (first path segment). */
    private static final ConcurrentHashMap<String, Set<String>> MISSING = new ConcurrentHashMap<>();

    /** ColorOS's names for its call history (and its renamed OPPO-era names): Android's call log is "calls". */
    private static final Set<String> CALL_HISTORY = new java.util.HashSet<>(java.util.Arrays.asList(
            "calls_with_normalized", "oppo_calls_with_normalized", "calls_contact_customized", "color_calls_customized",
            "customize_calls", "oppo_calls", "customize_dual_calls"));

    private ProviderCalls() {
    }

    /** The provider calls the fitting below needs, on a ContentResolver or a ContentProviderClient. */
    private interface Provider {
        Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort, CancellationSignal signal)
                throws RemoteException;

        Uri insert(Uri uri, ContentValues values) throws RemoteException;

        int update(Uri uri, ContentValues values, String selection, String[] args) throws RemoteException;

        int delete(Uri uri, String selection, String[] args) throws RemoteException;
    }

    private static Provider of(ContentResolver r) {
        return new Provider() {
            @Override
            public Cursor query(Uri uri, String[] p, String s, String[] a, String o, CancellationSignal c) {
                return r.query(uri, p, s, a, o, c);
            }

            @Override
            public Uri insert(Uri uri, ContentValues v) {
                return r.insert(uri, v);
            }

            @Override
            public int update(Uri uri, ContentValues v, String s, String[] a) {
                return r.update(uri, v, s, a);
            }

            @Override
            public int delete(Uri uri, String s, String[] a) {
                return r.delete(uri, s, a);
            }
        };
    }

    private static Provider of(ContentProviderClient client) {
        return new Provider() {
            @Override
            public Cursor query(Uri uri, String[] p, String s, String[] a, String o, CancellationSignal c) throws RemoteException {
                return client.query(uri, p, s, a, o, c);
            }

            @Override
            public Uri insert(Uri uri, ContentValues v) throws RemoteException {
                return client.insert(uri, v);
            }

            @Override
            public int update(Uri uri, ContentValues v, String s, String[] a) throws RemoteException {
                return client.update(uri, v, s, a);
            }

            @Override
            public int delete(Uri uri, String s, String[] a) throws RemoteException {
                return client.delete(uri, s, a);
            }
        };
    }

    private static boolean telephony(Uri uri) {
        if (uri == null) return false;
        String a = uri.getAuthority();
        return "com.android.contacts".equals(a) || "call_log".equals(a) || "sms".equals(a) || "mms".equals(a)
                || "mms-sms".equals(a) || "telephony".equals(a);
    }

    /**
     * ColorOS's call history paths as Android's call log (content://call_log/calls), keeping the rest of the path and
     * the parameters; Android's call log without the voicemail rows, which only the phone app may read or change.
     */
    static Uri map(Uri uri) {
        if (uri != null && "com.android.contacts".equals(uri.getAuthority())) return mapContacts(uri);
        if (uri == null || !"call_log".equals(uri.getAuthority())) return uri;
        List<String> path = uri.getPathSegments();
        boolean history = !path.isEmpty() && CALL_HISTORY.contains(path.get(0));
        if (!history && uri.getQueryParameter("allow_voicemails") == null) return uri;
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("allow_voicemails".equals(name)) continue;
            for (String v : uri.getQueryParameters(name)) b.appendQueryParameter(name, v);
        }
        if (history) {
            b.path("calls");
            for (String seg : path.subList(1, path.size())) b.appendPath(seg);
        }
        Uri out = b.build();
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "provider " + uri + " -> " + out);
        return out;
    }

    private static boolean history(Uri uri) {
        if (uri == null || !"call_log".equals(uri.getAuthority())) return false;
        List<String> path = uri.getPathSegments();
        return path.size() == 1 && CALL_HISTORY.contains(path.get(0));
    }

    /** ColorOS's phone lookup takes a SIM slot after the number (phone_lookup/NUMBER/SLOT); Android's the number only. */
    private static Uri mapContacts(Uri uri) {
        List<String> path = uri.getPathSegments();
        if (path.size() != 3 || !path.get(0).startsWith("phone_lookup")) return uri;
        Uri out = uri.buildUpon().path(path.get(0)).appendPath(path.get(1)).build();
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "provider " + uri + " -> " + out);
        return out;
    }

    /** What a column only ColorOS's providers have reads as: nothing (0 for numbers), a SIM-less call for simid. */
    static Object defaultFor(String column) {
        if (column == null) return DEFAULT;
        String bare = column.contains(".") ? column.substring(column.lastIndexOf('.') + 1) : column;
        switch (bare.toLowerCase(java.util.Locale.ROOT)) {
            case "simid":
                return -1;
        }
        return DEFAULT;
    }

    /** What stands for a lacking column in a selection: its default, else NULL (an ordinary row holds nothing there). */
    private static String substitute(String column) {
        Object d = defaultFor(column);
        return d != null ? String.valueOf(d) : "NULL";
    }

    private static Set<String> missing(Uri uri) {
        List<String> path = uri.getPathSegments();
        String key = uri.getAuthority() + "/" + (path.isEmpty() ? "" : path.get(0));
        return MISSING.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    }

    private static final Set<String> KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "select", "from", "where", "and", "or", "not", "null", "in", "is", "like", "glob", "between", "exists",
            "case", "when", "then", "else", "end", "limit", "offset", "order", "by", "group", "having", "asc", "desc",
            "collate", "distinct", "as", "on", "join", "union", "escape"));

    /** The column an error names as unknown, or null (an SQL keyword the provider refuses is not a column). */
    private static String unknownColumn(RuntimeException e) {
        if (e.getMessage() == null) return null;
        Matcher m = NO_COLUMN.matcher(e.getMessage());
        if (!m.find()) return null;
        String c = m.group(1);
        int dot = c.lastIndexOf('.');
        c = dot < 0 ? c : c.substring(dot + 1);
        return KEYWORDS.contains(c.toLowerCase(java.util.Locale.ROOT)) ? null : c;
    }

    /** Replaces whole-word uses of the lacking columns in a selection or sort order with their stand-in. */
    private static String rewrite(String sql, Set<String> missing) {
        if (sql == null || missing.isEmpty()) return sql;
        String out = sql;
        for (String c : missing) {
            out = out.replaceAll("(?<![A-Za-z0-9_'\"])(?:[A-Za-z0-9_]+\\.)?" + Pattern.quote(c) + "(?![A-Za-z0-9_'\"])", substitute(c));
        }
        return out;
    }

    private static String[] keep(String[] projection, Set<String> missing) {
        if (projection == null || missing.isEmpty()) return projection;
        List<String> kept = new ArrayList<>();
        for (String p : projection) {
            String bare = p.contains(".") ? p.substring(p.lastIndexOf('.') + 1) : p;
            if (!missing.contains(bare)) kept.add(p);
        }
        return kept.toArray(new String[0]);
    }

    private static ContentValues keep(ContentValues values, Set<String> missing) {
        if (values == null || missing.isEmpty()) return values;
        ContentValues out = new ContentValues(values);
        for (String c : missing) out.remove(c);
        return out;
    }

    private static Cursor shape(Cursor c, String[] projection) {
        if (c == null || projection == null) return c;
        for (String p : projection) {
            if (c.getColumnIndex(p) < 0) return new Columns(c, projection);
        }
        return c;
    }

    /** Each stock table's columns, asked once (no projection, no rows): some providers swallow a failed write. */
    private static final ConcurrentHashMap<String, Set<String>> STOCK = new ConcurrentHashMap<>();

    /** The stock table a write goes to and the URI that lists its columns, or null. */
    private static String[] table(Uri uri) {
        String a = uri.getAuthority();
        List<String> path = uri.getPathSegments();
        if ("call_log".equals(a)) return new String[]{"call_log", "content://call_log/calls"};
        if ("com.android.contacts".equals(a) && !path.isEmpty()) {
            String first = path.get(0);
            if (path.size() >= 3 && "raw_contacts".equals(first) && "data".equals(path.get(2))) first = "data";
            switch (first) {
                case "raw_contacts":
                case "data":
                case "groups":
                case "settings":
                    return new String[]{"contacts/" + first, "content://com.android.contacts/" + first};
            }
        }
        return null;
    }

    private static Set<String> columns(Provider p, String[] t) {
        Set<String> columns = STOCK.get(t[0]);
        if (columns != null) return columns;
        try (Cursor c = p.query(Uri.parse(t[1]), null, "0", null, null, null)) {
            if (c == null) return null;
            columns = new java.util.HashSet<>(java.util.Arrays.asList(c.getColumnNames()));
            STOCK.put(t[0], columns);
            return columns;
        } catch (RuntimeException | RemoteException e) {
            return null;
        }
    }

    private static ContentValues fit(Provider p, Uri uri, ContentValues values) {
        if (values == null) return null;
        if ("com.android.contacts".equals(uri.getAuthority())) values = LocalAccount.toAndroid(values);
        String[] t = table(uri);
        if (t == null) return values;
        Set<String> columns = columns(p, t);
        if (columns == null) return values;
        ContentValues out = new ContentValues(values);
        for (String key : values.keySet()) {
            if (columns.contains(key)) continue;
            out.remove(key);
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "provider has no " + t[0] + "." + key);
        }
        return out;
    }

    private static boolean callLog(Uri uri) {
        return uri != null && "call_log".equals(uri.getAuthority());
    }

    /** Android's call log columns, asked once: a projection of ColorOS's call history has a dozen it lacks. */
    private static void prune(Provider p, Uri uri, String[] projection, Set<String> missing) {
        if (projection == null || !callLog(uri)) return;
        Set<String> columns = columns(p, new String[]{"call_log", "content://call_log/calls"});
        if (columns == null) return;
        for (String c : projection) {
            String bare = c.contains(".") ? c.substring(c.lastIndexOf('.') + 1) : c;
            if (!columns.contains(bare) && bare.matches("[A-Za-z0-9_]+")) missing.add(bare);
        }
    }

    private static void learnt(Uri uri, String column) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "provider lacks " + column + " (" + uri + ")");
    }

    // The fitting, for either kind of caller.

    private static Cursor query(Provider p, Uri uri, String[] projection, String selection, String[] args, String sort,
                                CancellationSignal signal) throws RemoteException {
        // ColorOS's call history comes newest first; Android's call log has no order of its own without one.
        if (sort == null && history(uri)) sort = "date DESC";
        uri = map(uri);
        if ("com.android.contacts".equals(uri.getAuthority())) {
            LocalAccount.Where w = LocalAccount.toAndroid(selection, args);
            selection = w.selection;
            args = w.args;
        }
        Set<String> missing = missing(uri);
        boolean log = callLog(uri);
        prune(p, uri, projection, missing);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                Cursor c = p.query(uri, keep(projection, missing), rewrite(selection, missing), args, rewrite(sort, missing), signal);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "query " + uri + " where " + rewrite(selection, missing) + " " + java.util.Arrays.toString(args)
                            + " -> " + (c == null ? "null" : c.getCount()));
                }
                Cursor shaped = shape(c, projection);
                if (log) return FormattedNumbers.wrap(shaped);
                return "com.android.contacts".equals(uri.getAuthority()) ? LocalAccount.wrap(shaped) : shaped;
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                if (log && e instanceof SecurityException && projection != null) {
                    // No Call logs permission: an empty history, which the app shows as such, instead of a failed load.
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "call log not readable: " + e.getMessage());
                    return new android.database.MatrixCursor(projection, 0);
                }
                Log.w(TAG, "provider query refused: " + uri + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static Uri insert(Provider p, Uri uri, ContentValues values) throws RemoteException {
        uri = map(uri);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                return p.insert(uri, fit(p, uri, keep(values, missing)));
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "provider insert refused: " + uri + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static int update(Provider p, Uri uri, ContentValues values, String selection, String[] args) throws RemoteException {
        uri = map(uri);
        if ("com.android.contacts".equals(uri.getAuthority())) {
            LocalAccount.Where w = LocalAccount.toAndroid(selection, args);
            selection = w.selection;
            args = w.args;
        }
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                ContentValues kept = fit(p, uri, keep(values, missing));
                if (kept != null && kept.size() == 0) return 0;
                return p.update(uri, kept, rewrite(selection, missing), args);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "provider update refused: " + uri + ": " + e.getMessage());
                return 0;
            }
        }
        return 0;
    }

    private static int delete(Provider p, Uri uri, String selection, String[] args) throws RemoteException {
        uri = map(uri);
        if ("com.android.contacts".equals(uri.getAuthority())) {
            LocalAccount.Where w = LocalAccount.toAndroid(selection, args);
            selection = w.selection;
            args = w.args;
        }
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                return p.delete(uri, rewrite(selection, missing), args);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "provider delete refused: " + uri + ": " + e.getMessage());
                return 0;
            }
        }
        return 0;
    }

    private static RuntimeException unexpected(RemoteException e) {
        // A ContentResolver call never throws RemoteException (the provider died: Android answers as for a missing one).
        return new IllegalStateException(e);
    }

    // ContentResolver

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!telephony(uri)) return ColorOS.query(r, uri, projection, selection, args, sort);
        return query(r, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) {
        if (!telephony(uri)) return ColorOS.query(r, uri, projection, selection, args, sort, signal);
        try {
            return query(of(r), uri, projection, selection, args, sort, signal);
        } catch (RemoteException e) {
            throw unexpected(e);
        }
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        if (!telephony(uri)) return ColorOS.insert(r, uri, values);
        try {
            return insert(of(r), uri, values);
        } catch (RemoteException e) {
            throw unexpected(e);
        }
    }

    public static int update(ContentResolver r, Uri uri, ContentValues values, String selection, String[] args) {
        if (!telephony(uri)) return ColorOS.update(r, uri, values, selection, args);
        try {
            return update(of(r), uri, values, selection, args);
        } catch (RemoteException e) {
            throw unexpected(e);
        }
    }

    public static int delete(ContentResolver r, Uri uri, String selection, String[] args) {
        if (!telephony(uri)) return ColorOS.delete(r, uri, selection, args);
        try {
            return delete(of(r), uri, selection, args);
        } catch (RemoteException e) {
            throw unexpected(e);
        }
    }

    public static int bulkInsert(ContentResolver r, Uri uri, ContentValues[] values) {
        if (!telephony(uri)) return ColorOS.bulkInsert(r, uri, values);
        int n = 0;
        for (ContentValues v : values) if (insert(r, uri, v) != null) n++;
        return n;
    }

    public static android.content.ContentProviderResult[] applyBatch(ContentResolver r, String authority,
            ArrayList<android.content.ContentProviderOperation> operations)
            throws RemoteException, android.content.OperationApplicationException {
        if (!"com.android.contacts".equals(authority) && !"call_log".equals(authority)) {
            return ColorOS.applyBatch(r, authority, operations);
        }
        operations = fitBatch(operations);
        try {
            return r.applyBatch(authority, operations);
        } catch (SQLiteException | IllegalArgumentException e) {
            // A column Android's provider lacks (the batch was rolled back): again, fitted like single calls.
            if (unknownColumn(e) == null) throw e;
            Log.i(TAG, "provider batch refused (" + e.getMessage() + "), applied one by one");
        } catch (android.content.OperationApplicationException e) {
            // Android's contacts provider swallows an insert's unknown column (raw_contacts.phone_number) and answers
            // nothing: the batch was rolled back; again, fitted like single calls.
            if (e.getMessage() == null || !e.getMessage().contains("returned no result")) throw e;
            Log.i(TAG, "provider batch refused (" + e.getMessage() + "), applied one by one");
        }
        return oneByOne(of(r), operations);
    }

    // ContentProviderClient (ColorOS's ProviderUtils reads through one)

    public static Cursor query(ContentProviderClient c, Uri uri, String[] projection, String selection, String[] args, String sort)
            throws RemoteException {
        return query(c, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentProviderClient c, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) throws RemoteException {
        if (!telephony(uri)) return c.query(uri, projection, selection, args, sort, signal);
        return query(of(c), uri, projection, selection, args, sort, signal);
    }

    public static Uri insert(ContentProviderClient c, Uri uri, ContentValues values) throws RemoteException {
        if (!telephony(uri)) return c.insert(uri, values);
        return insert(of(c), uri, values);
    }

    public static int update(ContentProviderClient c, Uri uri, ContentValues values, String selection, String[] args)
            throws RemoteException {
        if (!telephony(uri)) return c.update(uri, values, selection, args);
        return update(of(c), uri, values, selection, args);
    }

    public static int delete(ContentProviderClient c, Uri uri, String selection, String[] args) throws RemoteException {
        if (!telephony(uri)) return c.delete(uri, selection, args);
        return delete(of(c), uri, selection, args);
    }

    public static int bulkInsert(ContentProviderClient c, Uri uri, ContentValues[] values) throws RemoteException {
        if (!telephony(uri)) return c.bulkInsert(uri, values);
        int n = 0;
        for (ContentValues v : values) if (insert(c, uri, v) != null) n++;
        return n;
    }

    public static android.content.ContentProviderResult[] applyBatch(ContentProviderClient c,
            ArrayList<android.content.ContentProviderOperation> operations)
            throws RemoteException, android.content.OperationApplicationException {
        operations = fitBatch(operations);
        try {
            return c.applyBatch(operations);
        } catch (SQLiteException | IllegalArgumentException e) {
            if (unknownColumn(e) == null) throw e;
            Log.i(TAG, "provider batch refused (" + e.getMessage() + "), applied one by one");
        } catch (android.content.OperationApplicationException e) {
            // Android's contacts provider swallows an insert's unknown column (raw_contacts.phone_number) and answers
            // nothing: the batch was rolled back; again, fitted like single calls.
            if (e.getMessage() == null || !e.getMessage().contains("returned no result")) throw e;
            Log.i(TAG, "provider batch refused (" + e.getMessage() + "), applied one by one");
        }
        return oneByOne(of(c), operations);
    }

    /** The contacts provider's paths Android has (ColorOS adds contact_uri_query, ringtone_update, ... of its own). */
    private static final Set<String> CONTACTS_PATHS = new java.util.HashSet<>(java.util.Arrays.asList(
            "contacts", "raw_contacts", "data", "groups", "groups_summary", "settings", "aggregation_exceptions",
            "profile", "phone_lookup", "phone_lookup_enterprise", "directories", "directories_enterprise",
            "status_updates", "stream_items", "stream_items_photos", "photo_dimensions", "provider_status",
            "search_suggest_query", "search_suggest_shortcut", "deleted_contacts", "raw_contact_entities",
            "raw_contact_entities_corp", "display_photo", "sim_accounts", "metadata_sync", "metadata_sync_state",
            "directory_file_enterprise"));

    /**
     * A contacts batch as Android's provider takes it, still applied as one transaction: an operation on a path only
     * ColorOS's provider has (the editor's contact_uri_query and ringtone_update) becomes a query that changes nothing
     * (same position, so back references and the app's reading of the results stay right), and a raw contact or group
     * inserted under ColorOS's local account goes to Android's local account.
     */
    private static ArrayList<android.content.ContentProviderOperation> fitBatch(
            ArrayList<android.content.ContentProviderOperation> operations) {
        ArrayList<android.content.ContentProviderOperation> out = null;
        for (int i = 0; i < operations.size(); i++) {
            android.content.ContentProviderOperation op = operations.get(i);
            Uri uri = op.getUri();
            if (uri == null || !"com.android.contacts".equals(uri.getAuthority())) continue;
            List<String> path = uri.getPathSegments();
            android.content.ContentProviderOperation fitted = null;
            if (path.isEmpty() || !CONTACTS_PATHS.contains(path.get(0))) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "provider batch: no " + uri + ", skipped");
                fitted = android.content.ContentProviderOperation
                        .newAssertQuery(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                        .withSelection("_id=-1", null).withExpectedCount(0).build();
            } else if (op.isInsert() && path.size() == 1 && ("raw_contacts".equals(path.get(0)) || "groups".equals(path.get(0)))) {
                try {
                    ContentValues values = op.resolveValueBackReferences(new android.content.ContentProviderResult[i], i);
                    ContentValues local = LocalAccount.toAndroid(values);
                    if (local != values) {
                        fitted = android.content.ContentProviderOperation.newInsert(uri).withValues(local)
                                .withYieldAllowed(op.isYieldAllowed()).build();
                    }
                } catch (RuntimeException e) {
                    // Values that refer back to other results: not an account the app chose, left as they are.
                }
            }
            if (fitted == null) continue;
            if (out == null) out = new ArrayList<>(operations);
            out.set(i, fitted);
        }
        return out != null ? out : operations;
    }

    /** One at a time through the same fitting as single calls; back references resolve from the results so far. */
    private static android.content.ContentProviderResult[] oneByOne(Provider p,
            ArrayList<android.content.ContentProviderOperation> operations) throws android.content.OperationApplicationException {
        Through through = new Through(p);
        android.content.ContentProviderResult[] results = new android.content.ContentProviderResult[operations.size()];
        for (int i = 0; i < results.length; i++) results[i] = operations.get(i).apply(through, results, i);
        return results;
    }

    /** A provider object (never registered) for ContentProviderOperation.apply to call. */
    private static final class Through extends android.content.ContentProvider {
        private final Provider p;

        Through(Provider p) {
            this.p = p;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            try {
                return ProviderCalls.query(p, uri, projection, selection, args, sort, null);
            } catch (RemoteException e) {
                throw unexpected(e);
            }
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            try {
                return ProviderCalls.insert(p, uri, values);
            } catch (RemoteException e) {
                throw unexpected(e);
            }
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            try {
                return ProviderCalls.delete(p, uri, selection, args);
            } catch (RemoteException e) {
                throw unexpected(e);
            }
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            try {
                return ProviderCalls.update(p, uri, values, selection, args);
            } catch (RemoteException e) {
                throw unexpected(e);
            }
        }
    }
}
