package org.ungoogled.ui;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * microG Maps' "Pull from Google account": copies the signed-in account's saved lists into
 * Local saved. microG Maps saves to the account, as Maps does, and Maps keeps a copy of the
 * account's lists on the phone (gmm_sync.db, table sync_item_data): a row per list (corpus
 * SAVES_LIST) and a row per saved place (SAVES_ITEM), each holding the protobuf Maps synced.
 * They are only read here; saving, editing and syncing stay with Maps.
 *
 * Fields read, from Maps 26.36's protos:
 *   list   item_proto 1 { 1 { 2 type, 5 title } }; its id is server_id, client_id before its first sync
 *   place  item_proto 1 { 1 { 2 place { 5 name, 6 { 3 lat, 4 lng }, 7 { 1 cell, 2 fingerprint } },
 *          4 note, 8 { 1 { 1 [ { 3 list id } ] } } } }, with the feature_fprint, latitude_e6 and
 *          longitude_e6 columns as a fallback
 * A row in sync_state 3 is a deletion still on its way to the account, and Maps skips it too.
 */
final class AccountSaves {
    static final String KEY_PULLED_AT = "account_pulled_at";
    private static final String DB = "gmm_sync.db";
    private static final int SAVES_LIST = 13, SAVES_ITEM = 14, DELETED = 3;
    /** Maps' list types (EntityListType); wishlist is Travel plans, "just save" a place in no list. */
    private static final int FAVORITES = 2, WANT_TO_GO = 3, STARRED = 4, WISHLIST = 5, JUST_SAVE = 6;
    /** Ids here of the account's own lists. */
    private static final String OWN = "g:";

    private AccountSaves() {}

    /** What a pull found (lists, not counting places saved in none), and how many of its places are new here. */
    static final class Result {
        int lists, places, added;
    }

    static Result pull(Context c) throws Exception {
        File file = c.getDatabasePath(DB);
        if (!file.exists()) return new Result();   // never synced anything: nothing to pull
        Map<String, String> where = new HashMap<>();          // account list id -> list here, "" = none
        Map<String, String> names = new LinkedHashMap<>();    // list here -> name, for the account's own lists
        List<SavedStore.Place> found = new ArrayList<>();
        int lists = 0;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor q = db.rawQuery("SELECT client_id, server_id, item_proto FROM sync_item_data WHERE corpus = ? AND sync_state != ?",
                    new String[]{Integer.toString(SAVES_LIST), Integer.toString(DELETED)})) {
                while (q.moveToNext()) {
                    String client = q.getString(0), server = q.getString(1);
                    String id = server != null && !server.isEmpty() ? server : client;
                    if (id == null || id.isEmpty()) continue;
                    Msg list = new Msg(q.getBlob(2)).msg(1).msg(1);
                    String here = listHere((int) list.num(2), id);
                    if (here.startsWith(OWN)) {
                        String title = list.str(5).trim();
                        names.put(here, title.isEmpty() ? "Saved list" : title);
                    }
                    if (client != null && !client.isEmpty()) where.put(client, here);
                    if (server != null && !server.isEmpty()) where.put(server, here);
                    if (!here.isEmpty()) lists++;
                }
            }
            try (Cursor q = db.rawQuery("SELECT feature_fprint, latitude_e6, longitude_e6, timestamp, item_proto FROM sync_item_data "
                    + "WHERE corpus = ? AND sync_state != ?", new String[]{Integer.toString(SAVES_ITEM), Integer.toString(DELETED)})) {
                while (q.moveToNext()) {
                    SavedStore.Place p = place(q, where);
                    if (p != null) found.add(p);
                }
            }
        } finally {
            db.close();
        }
        // Oldest first, so the newest saves come out on top here as well.
        found.sort((a, b) -> Long.compare(a.added, b.added));
        Result r = new Result();
        r.lists = lists;
        r.places = found.size();
        if (found.isEmpty()) return r;
        r.added = SavedStore.mergePulled(c, names, found);
        c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_PULLED_AT, System.currentTimeMillis()).apply();
        return r;
    }

    /** Where an account list goes here: Maps' own lists onto the same lists here, the user's own lists by their id. */
    private static String listHere(int type, String id) {
        switch (type) {
            case FAVORITES: return SavedStore.FAVOURITES;
            case WANT_TO_GO: return SavedStore.WANT_TO_GO;
            case STARRED: return SavedStore.STARRED;
            case WISHLIST: return SavedStore.TRAVEL;
            case JUST_SAVE: return "";
            default: return OWN + id;
        }
    }

    /** One saved place, or null for a saved item that is not a place. */
    private static SavedStore.Place place(Cursor q, Map<String, String> where) {
        Msg item = new Msg(q.getBlob(4)).msg(1).msg(1);
        // The place: the item itself, or inside one of the item kinds that carry a place.
        Msg place = item.has(2) ? item.msg(2) : item.has(12) ? item.msg(12).msg(3) : item.msg(17).msg(1);
        SavedStore.Place p = new SavedStore.Place();
        Msg id = place.msg(7);
        long cell = id.num(1), fingerprint = id.num(2);
        if (fingerprint == 0 && !q.isNull(0)) fingerprint = q.getLong(0);
        if (fingerprint != 0) p.ftid = "0x" + Long.toHexString(cell) + ":0x" + Long.toHexString(fingerprint);
        Msg at = place.msg(6);
        if (at.has(3) && at.has(4)) {
            p.lat = at.dbl(3);
            p.lng = at.dbl(4);
        } else if (!q.isNull(1) && !q.isNull(2)) {
            p.lat = q.getInt(1) / 1e6;
            p.lng = q.getInt(2) / 1e6;
        } else if (p.ftid == null) {
            return null;
        }
        String name = place.str(5);
        if (name.isEmpty()) name = place.str(3);
        if (name.isEmpty()) name = item.str(3);
        p.name = name.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : name;
        p.note = item.str(4).trim();
        long t = q.isNull(3) ? 0 : q.getLong(3);
        p.added = t <= 0 ? System.currentTimeMillis() : t < 100_000_000_000L ? t * 1000 : t > 100_000_000_000_000L ? t / 1000 : t;
        for (Msg ref : item.msg(8).msg(1).msgs(1)) {
            String here = where.get(ref.str(3));
            if (here != null && !here.isEmpty()) p.lists.add(here);
        }
        return p;
    }

    /** A protobuf message read just far enough: each field's values in order, numbers as Long, the rest as bytes. */
    static final class Msg {
        private static final Msg EMPTY = new Msg(null);
        private final Map<Integer, List<Object>> fields = new HashMap<>();

        Msg(byte[] b) {
            if (b == null) return;
            try {
                read(b);
            } catch (RuntimeException e) {
                fields.clear();   // not a message: nothing to read
            }
        }

        private void read(byte[] b) {
            int[] at = {0};
            while (at[0] < b.length) {
                long key = varint(b, at);
                Object v;
                switch ((int) (key & 7)) {
                    case 0: v = varint(b, at); break;
                    case 1: v = little(b, at, 8); break;
                    case 2: {
                        long n = varint(b, at);
                        if (n < 0 || at[0] + n > b.length) throw new IllegalArgumentException("truncated");
                        v = Arrays.copyOfRange(b, at[0], at[0] + (int) n);
                        at[0] += (int) n;
                        break;
                    }
                    case 5: v = little(b, at, 4); break;
                    default: throw new IllegalArgumentException("wire type " + (key & 7));
                }
                int field = (int) (key >>> 3);
                List<Object> values = fields.get(field);
                if (values == null) fields.put(field, values = new ArrayList<>());
                values.add(v);
            }
        }

        private static long varint(byte[] b, int[] at) {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at[0] >= b.length) throw new IllegalArgumentException("truncated");
                byte x = b[at[0]++];
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint too long");
        }

        private static long little(byte[] b, int[] at, int n) {
            if (at[0] + n > b.length) throw new IllegalArgumentException("truncated");
            long v = 0;
            for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[at[0] + i] & 0xff);
            at[0] += n;
            return v;
        }

        boolean has(int field) {
            return fields.containsKey(field);
        }

        private Object first(int field) {
            List<Object> values = fields.get(field);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        Msg msg(int field) {
            Object o = first(field);
            return o instanceof byte[] ? new Msg((byte[]) o) : EMPTY;
        }

        List<Msg> msgs(int field) {
            List<Msg> out = new ArrayList<>();
            List<Object> values = fields.get(field);
            if (values != null) for (Object o : values) if (o instanceof byte[]) out.add(new Msg((byte[]) o));
            return out;
        }

        String str(int field) {
            Object o = first(field);
            return o instanceof byte[] ? new String((byte[]) o, StandardCharsets.UTF_8) : "";
        }

        long num(int field) {
            Object o = first(field);
            return o instanceof Long ? (Long) o : 0;
        }

        double dbl(int field) {
            Object o = first(field);
            return o instanceof Long ? Double.longBitsToDouble((Long) o) : 0;
        }
    }
}
