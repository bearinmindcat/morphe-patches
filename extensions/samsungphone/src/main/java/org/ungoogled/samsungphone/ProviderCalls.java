package org.ungoogled.samsungphone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samsung Phone reads and writes Android's contacts provider as if it were Samsung's, which has more columns
 * (sec_name_initial, sec_custom_vibration, sec_led, ...) and paths, and its call history from Samsung's own "logs"
 * provider, which only One UI has. Off One UI an unknown column fails the query or write ("Invalid column ...") and an
 * unknown path throws. The patch sends the app's ContentResolver calls here: other providers are passed straight
 * through; Samsung's call history goes to Android's call log (content://logs/call -> content://call_log/calls, without
 * the voicemail rows only the phone app may read); for these an unknown path answers nothing, a refused one (no
 * Call logs permission) answers nothing, and a column they lack is learnt from the error, left out of the call
 * (writes), read as its default (queries) or replaced by it (selections, sort orders: 0, a call's logtype 100), and
 * Samsung's UNICODE_NATURAL collation becomes Android's LOCALIZED.
 */
public final class ProviderCalls {
    private static final String TAG = "UA";
    static final Object DEFAULT = null;
    private static final Pattern NO_COLUMN = Pattern.compile("(?:no such column: |has no column named |Invalid column |Invalid token )([A-Za-z0-9_.]+)");

    /** Columns the provider lacks, per authority and table (first path segment). */
    private static final ConcurrentHashMap<String, Set<String>> MISSING = new ConcurrentHashMap<>();

    private ProviderCalls() {
    }

    private static boolean telephony(Uri uri) {
        if (uri == null) return false;
        String a = uri.getAuthority();
        return "sms".equals(a) || "mms".equals(a) || "mms-sms".equals(a) || "com.android.contacts".equals(a)
                || "call_log".equals(a) || "logs".equals(a) || "com.android.blockednumber".equals(a);
    }

    /**
     * What a column only Samsung's providers have reads as, and stands for in a selection: an entry of Android's call
     * log is a voice call (logtype 100) that is not deleted, rejected or reported as spam.
     */
    static Object defaultFor(String column) {
        if (column == null) return DEFAULT;
        String bare = column.contains(".") ? column.substring(column.lastIndexOf('.') + 1) : column;
        switch (bare.toLowerCase(java.util.Locale.ROOT)) {
            case "logtype":
                return 100;
            case "deleted":
            case "reject_flag":
            case "spam_report":
                return 0;
        }
        return DEFAULT;
    }

    /**
     * What stands for a lacking column in a selection: its default, else 0 for the contacts and telephony providers,
     * NULL for the call log (what Samsung's own call history holds in these columns for an ordinary call, so
     * "service_type IS NULL" still picks ordinary calls).
     */
    private static String substitute(String column, boolean callLog) {
        Object d = defaultFor(column);
        if (d != null) return String.valueOf(d);
        return callLog ? "NULL" : "0";
    }

    /**
     * Samsung's call history (content://logs/call, its search and its single entries) as Android's call log; its other
     * logs (memos, durations, roaming) have no Android counterpart and stay unknown. Android's call log without the
     * voicemail rows: those only the phone app may read (SecurityException).
     */
    static Uri map(Uri uri) {
        if (uri == null) return null;
        String a = uri.getAuthority();
        if (!"logs".equals(a) && !"call_log".equals(a)) return uri;
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("allow_voicemails".equals(name)) continue;
            for (String v : uri.getQueryParameters(name)) b.appendQueryParameter(name, v);
        }
        if ("logs".equals(a)) {
            List<String> path = uri.getPathSegments();
            if (path.isEmpty() || !"call".equals(path.get(0))) return uri;
            b.authority("call_log").path("calls");
            if (path.size() >= 3 && "search".equals(path.get(1))) {
                b.appendPath("filter");
                for (String seg : path.subList(2, path.size())) b.appendPath(seg);
            } else if (path.size() >= 2 && !"search_log".equals(path.get(1)) && !"search".equals(path.get(1))) {
                for (String seg : path.subList(1, path.size())) b.appendPath(seg);
            } else if (path.size() >= 2) {
                return uri;
            }
        }
        Uri out = b.build();
        if (Log.isLoggable(TAG, Log.VERBOSE) && !out.equals(uri)) Log.v(TAG, "provider " + uri + " -> " + out);
        return out;
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

    private static final Pattern SORT_LIMIT = Pattern.compile(
            "(?i)^(.*?)\\s+LIMIT\\s+(\\d+)(?:\\s*,\\s*(\\d+)|\\s+OFFSET\\s+(\\d+))?\\s*$");

    /** Replaces whole-word uses of the lacking columns in a selection or sort order with 0. */
    private static String rewrite(String sql, Set<String> missing) {
        return rewrite(sql, missing, false);
    }

    private static String rewrite(String sql, Set<String> missing, boolean callLog) {
        if (sql == null) return null;
        // Samsung's SQLite collation for natural name order; Android's providers have LOCALIZED.
        String out = sql.replace("UNICODE_NATURAL", "LOCALIZED");
        for (String c : missing) {
            out = out.replaceAll("(?<![A-Za-z0-9_'\"])(?:[A-Za-z0-9_]+\\.)?" + Pattern.quote(c) + "(?![A-Za-z0-9_'\"])", substitute(c, callLog));
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

    /** Each stock table's columns, asked once (no projection, no rows): SmsProvider swallows a failed write. */
    private static final ConcurrentHashMap<String, Set<String>> STOCK = new ConcurrentHashMap<>();

    /** The stock table a write goes to and the URI that lists its columns, or null. */
    private static String[] table(Uri uri) {
        String a = uri.getAuthority();
        List<String> path = uri.getPathSegments();
        if ("sms".equals(a)) return new String[]{"sms", "content://sms"};
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
            return null;
        }
        if ("mms".equals(a)) {
            if (path.contains("part") || (!path.isEmpty() && "part".equals(path.get(0)))) return new String[]{"part", "content://mms/part"};
            if (path.contains("addr")) return null;
            return new String[]{"pdu", "content://mms"};
        }
        return null;
    }

    private static ContentValues fit(ContentResolver r, Uri uri, ContentValues values) {
        if (values == null) return null;
        String[] t = table(uri);
        if (t == null) return values;
        Set<String> columns = STOCK.get(t[0]);
        if (columns == null) {
            try (Cursor c = r.query(Uri.parse(t[1]), null, "0", null, null)) {
                if (c == null) return values;
                columns = new java.util.HashSet<>(java.util.Arrays.asList(c.getColumnNames()));
                STOCK.put(t[0], columns);
            } catch (RuntimeException e) {
                return values;
            }
        }
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

    /** Android's call log columns, asked once: a projection of Samsung's call history has dozens it lacks. */
    private static void prune(ContentResolver r, Uri uri, String[] projection, Set<String> missing) {
        if (projection == null || !callLog(uri)) return;
        Set<String> columns = STOCK.get("call_log");
        if (columns == null) {
            try (Cursor c = r.query(android.provider.CallLog.Calls.CONTENT_URI, null, "0", null, null)) {
                if (c == null) return;
                columns = new java.util.HashSet<>(java.util.Arrays.asList(c.getColumnNames()));
                STOCK.put("call_log", columns);
            } catch (RuntimeException e) {
                return;
            }
        }
        for (String p : projection) {
            String bare = p.contains(".") ? p.substring(p.lastIndexOf('.') + 1) : p;
            if (!columns.contains(bare) && bare.matches("[A-Za-z0-9_]+")) missing.add(bare);
        }
    }

    private static void learnt(Uri uri, String column) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "provider lacks " + column + " (" + uri + ")");
    }

    // ContentResolver

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort) {
        return query(r, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) {
        if (!telephony(uri)) return r.query(uri, projection, selection, args, sort, signal);
        uri = map(uri);
        Set<String> missing = missing(uri);
        boolean log = callLog(uri);
        prune(r, uri, projection, missing);
        if (log && sort != null) {
            // Samsung's provider takes "... LIMIT n" in the sort order; Android's call log refuses it and takes a
            // limit (and offset) parameter instead.
            Matcher m = SORT_LIMIT.matcher(sort);
            if (m.matches()) {
                sort = m.group(1);
                Uri.Builder b = uri.buildUpon();
                if (m.group(3) != null) {
                    b.appendQueryParameter("limit", m.group(3)).appendQueryParameter("offset", m.group(2));
                } else {
                    b.appendQueryParameter("limit", m.group(2));
                    if (m.group(4) != null) b.appendQueryParameter("offset", m.group(4));
                }
                uri = b.build();
            }
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                Cursor c = r.query(uri, keep(projection, missing), rewrite(selection, missing, log), args, rewrite(sort, missing, log), signal);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "query " + uri + " where " + rewrite(selection, missing, log) + " " + java.util.Arrays.toString(args)
                            + " -> " + (c == null ? "null" : c.getCount()));
                }
                return shape(c, projection);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                if (log && e instanceof SecurityException && projection != null) {
                    // No Call logs permission (see CallHistoryNote): an empty history, which the app shows as such,
                    // instead of a failed load it retries every few seconds.
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "call log not readable: " + e.getMessage());
                    return new android.database.MatrixCursor(projection, 0);
                }
                Log.w(TAG, "provider query refused: " + uri + ": " + e.getMessage());
                if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "  where " + rewrite(selection, missing, log) + " order " + rewrite(sort, missing, log));
                return null;
            }
        }
        return null;
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        if (!telephony(uri)) return r.insert(uri, values);
        uri = map(uri);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                return r.insert(uri, fit(r, uri, keep(values, missing)));
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

    public static int update(ContentResolver r, Uri uri, ContentValues values, String selection, String[] args) {
        if (!telephony(uri)) return r.update(uri, values, selection, args);
        uri = map(uri);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                ContentValues kept = fit(r, uri, keep(values, missing));
                if (kept != null && kept.size() == 0) return 0;
                return r.update(uri, kept, rewrite(selection, missing, callLog(uri)), args);
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

    public static int delete(ContentResolver r, Uri uri, String selection, String[] args) {
        if (!telephony(uri)) return r.delete(uri, selection, args);
        uri = map(uri);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                return r.delete(uri, rewrite(selection, missing, callLog(uri)), args);
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

    public static android.content.ContentProviderResult[] applyBatch(ContentResolver r, String authority,
            ArrayList<android.content.ContentProviderOperation> operations)
            throws android.os.RemoteException, android.content.OperationApplicationException {
        if (!"com.android.contacts".equals(authority) && !"logs".equals(authority) && !"call_log".equals(authority)) {
            return r.applyBatch(authority, operations);
        }
        // One at a time through the same fitting as single calls; back references resolve from the results so far.
        Through through = new Through(r);
        android.content.ContentProviderResult[] results = new android.content.ContentProviderResult[operations.size()];
        for (int i = 0; i < results.length; i++) results[i] = operations.get(i).apply(through, results, i);
        return results;
    }

    /** A provider object (never registered) for ContentProviderOperation.apply to call. */
    private static final class Through extends android.content.ContentProvider {
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
            return ProviderCalls.query(r, uri, projection, selection, args, sort);
        }

        @Override
        public String getType(Uri uri) {
            return r.getType(uri);
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return ProviderCalls.insert(r, uri, values);
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return ProviderCalls.delete(r, uri, selection, args);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return ProviderCalls.update(r, uri, values, selection, args);
        }
    }

    /**
     * Samsung's call settings provider (part of One UI's phone service) blocks and unblocks numbers; Android's own
     * blocked-number list accepts only the default phone app (SecurityException), so this app cannot block anything
     * itself. Blocking or unblocking opens Android's blocked numbers screen (the phone app's list) instead, and a
     * number never reads as blocked here. Other providers are passed straight through.
     */
    public static android.os.Bundle call(ContentResolver r, Uri uri, String method, String arg, android.os.Bundle extras) {
        if (uri != null && CALL_SETTINGS.equals(uri.getAuthority())) return callSettings(method, arg);
        return r.call(uri, method, arg, extras);
    }

    public static android.os.Bundle call(ContentResolver r, String authority, String method, String arg, android.os.Bundle extras) {
        if (CALL_SETTINGS.equals(authority)) return callSettings(method, arg);
        return r.call(authority, method, arg, extras);
    }

    private static final String CALL_SETTINGS = "com.android.phone.callsettings";

    private static android.os.Bundle callSettings(String method, String arg) {
        if ("blocking_provider_number".equals(method) || "unblocking_provider_number".equals(method)) {
            Blocking.manage(arg, "blocking_provider_number".equals(method));
        } else if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "no call settings provider for " + method);
        }
        return null;
    }

    public static int bulkInsert(ContentResolver r, Uri uri, ContentValues[] values) {
        if (!telephony(uri)) return r.bulkInsert(uri, values);
        int n = 0;
        for (ContentValues v : values) if (insert(r, uri, v) != null) n++;
        return n;
    }
}
