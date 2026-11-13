package org.ungoogled.samsungfinder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * A search source for Finder in Samsung's own contract (a provider for com.samsung.android.intent.action.DEVICE_SEARCH,
 * as One UI's Settings and My Files have): Finder finds it like any other, asks "getInfo" for its name and icon, and
 * queries it with ?query=...&limit=...; the answer is one row, "count" and "result" (JSON: categories of items).
 */
abstract class SearchSource extends ContentProvider {
    /** The source's name, icon and the app its header opens. */
    abstract String label();

    abstract Intent launchIntent();

    Intent inAppSearchIntent() {
        return null;
    }

    /** The items matching [query], at most [limit], as Finder's JSON items. */
    abstract List<JSONObject> search(String query, int limit) throws JSONException;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"getInfo".equals(method)) return null;
        Bundle info = new Bundle();
        info.putString("label", label());
        Intent launch = launchIntent();
        if (launch != null) {
            info.putParcelable("launchIntent", launch);
            Icon icon = iconOf(launch);
            if (icon != null) info.putParcelable("icon", icon);
        }
        Intent inApp = inAppSearchIntent();
        if (inApp != null) info.putParcelable("inAppSearchActivity", inApp);
        return info;
    }

    private Icon iconOf(Intent launch) {
        try {
            android.content.pm.ResolveInfo r = getContext().getPackageManager().resolveActivity(launch, 0);
            if (r == null || r.activityInfo == null) return null;
            int res = r.getIconResource();
            return res == 0 ? null : Icon.createWithResource(r.activityInfo.packageName, res);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String query = uri.getQueryParameter("query");
        if (query == null || query.trim().isEmpty()) return null;
        int limit = 100;
        try {
            limit = Integer.parseInt(uri.getQueryParameter("limit"));
        } catch (RuntimeException e) {
            // Finder always sends one; 100 otherwise.
        }
        try {
            List<JSONObject> items = search(query.trim(), limit);
            if (items.isEmpty()) return null;
            JSONArray array = new JSONArray();
            for (JSONObject item : items) array.put(item);
            JSONObject category = new JSONObject()
                    .put("label", label())
                    .put("totalCount", String.valueOf(items.size()))
                    .put("items", array);
            JSONObject result = new JSONObject().put("categories", new JSONArray().put(category));
            MatrixCursor cursor = new MatrixCursor(new String[] {"count", "result"});
            cursor.addRow(new Object[] {String.valueOf(items.size()), result.toString()});
            return cursor;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Finder's "view" action for an item: the intent it starts when the item is tapped, parcelled ("intent_str"), since
     * the URI form ("intent_data") loses the read grant a file needs.
     */
    static String viewAction(Intent intent) throws JSONException {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            String parcelled = android.util.Base64.encodeToString(parcel.marshall(), android.util.Base64.NO_WRAP);
            return new JSONObject().put("intent_str", parcelled).toString();
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
