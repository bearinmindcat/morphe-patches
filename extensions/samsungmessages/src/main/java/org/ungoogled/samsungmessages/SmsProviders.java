package org.ungoogled.samsungmessages;

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
 * Messages talks to Android's telephony provider (sms, mms, mms-sms) as if it were Samsung's, which has more columns
 * (reserved, group_id, sim_slot, ...) and more paths (im-threads, spam-messages, ...), and to the contacts provider
 * with Samsung's columns (sec_primary_name_initial, ...). Off One UI the providers are Android's own: an unknown path throws ("Unrecognized URI") and an unknown column fails the query or write. The patch
 * sends the app's ContentResolver calls here: other providers are passed straight through; for the telephony
 * provider an unknown path answers nothing, and a column it lacks is learnt from the error, left out of the call
 * (writes), read as null (queries) or as 0 (selections, sort orders).
 */
public final class SmsProviders {
    private static final String TAG = "UA";
    static final Object DEFAULT = null;
    private static final Pattern NO_COLUMN = Pattern.compile("(?:no such column: |has no column named |Invalid column )([A-Za-z0-9_.]+)");

    /** Columns the provider lacks, per authority and table (first path segment). */
    private static final ConcurrentHashMap<String, Set<String>> MISSING = new ConcurrentHashMap<>();

    private SmsProviders() {
    }

    private static boolean telephony(Uri uri) {
        if (uri == null) return false;
        String a = uri.getAuthority();
        return "sms".equals(a) || "mms".equals(a) || "mms-sms".equals(a) || "com.android.contacts".equals(a);
    }

    private static Set<String> missing(Uri uri) {
        List<String> path = uri.getPathSegments();
        String key = uri.getAuthority() + "/" + (path.isEmpty() ? "" : path.get(0));
        return MISSING.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    }

    /** The column an error names as unknown, or null. */
    private static String unknownColumn(RuntimeException e) {
        if (e.getMessage() == null) return null;
        Matcher m = NO_COLUMN.matcher(e.getMessage());
        if (!m.find()) return null;
        String c = m.group(1);
        int dot = c.lastIndexOf('.');
        return dot < 0 ? c : c.substring(dot + 1);
    }

    /** Replaces whole-word uses of the lacking columns in a selection or sort order with 0. */
    private static String rewrite(String sql, Set<String> missing) {
        if (sql == null || missing.isEmpty()) return sql;
        String out = sql;
        for (String c : missing) {
            out = out.replaceAll("(?<![A-Za-z0-9_'\"])(?:[A-Za-z0-9_]+\\.)?" + Pattern.quote(c) + "(?![A-Za-z0-9_'\"])", "0");
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
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "telephony provider has no " + t[0] + "." + key);
        }
        return out;
    }

    private static void learnt(Uri uri, String column) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "telephony provider lacks " + column + " (" + uri + ")");
    }

    // ContentResolver

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort) {
        return query(r, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentResolver r, Uri uri, String[] projection, String selection, String[] args, String sort,
                               CancellationSignal signal) {
        if (!telephony(uri)) return r.query(uri, projection, selection, args, sort, signal);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 16; attempt++) {
            try {
                Cursor c = r.query(uri, keep(projection, missing), rewrite(selection, missing), args, rewrite(sort, missing), signal);
                return shape(c, projection);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "telephony provider query refused: " + uri + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public static Uri insert(ContentResolver r, Uri uri, ContentValues values) {
        if (!telephony(uri)) return r.insert(uri, values);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 16; attempt++) {
            try {
                return r.insert(uri, fit(r, uri, keep(values, missing)));
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "telephony provider insert refused: " + uri + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public static int update(ContentResolver r, Uri uri, ContentValues values, String selection, String[] args) {
        if (!telephony(uri)) return r.update(uri, values, selection, args);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 16; attempt++) {
            try {
                ContentValues kept = fit(r, uri, keep(values, missing));
                if (kept != null && kept.size() == 0) return 0;
                return r.update(uri, kept, rewrite(selection, missing), args);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "telephony provider update refused: " + uri + ": " + e.getMessage());
                return 0;
            }
        }
        return 0;
    }

    public static int delete(ContentResolver r, Uri uri, String selection, String[] args) {
        if (!telephony(uri)) return r.delete(uri, selection, args);
        Set<String> missing = missing(uri);
        for (int attempt = 0; attempt < 16; attempt++) {
            try {
                return r.delete(uri, rewrite(selection, missing), args);
            } catch (SQLiteException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                String column = unknownColumn(e);
                if (column != null && missing.add(column)) {
                    learnt(uri, column);
                    continue;
                }
                Log.w(TAG, "telephony provider delete refused: " + uri + ": " + e.getMessage());
                return 0;
            }
        }
        return 0;
    }

    public static int bulkInsert(ContentResolver r, Uri uri, ContentValues[] values) {
        if (!telephony(uri)) return r.bulkInsert(uri, values);
        int n = 0;
        for (ContentValues v : values) if (insert(r, uri, v) != null) n++;
        return n;
    }
}
