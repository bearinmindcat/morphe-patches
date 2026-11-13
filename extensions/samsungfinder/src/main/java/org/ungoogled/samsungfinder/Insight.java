package org.ungoogled.samsungfinder;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One UI's "Insight Search": the apps Finder searches in. Samsung's apps mark their search provider for it with
 * extra-attr entries in their searchable XML (includeInInsightSearch, searchLayoutStyle), which only One UI's search
 * service reads. Here the same list comes from Android's own searchables (SearchManager.getSearchableInfo), with those
 * entries read from each app's XML; apps that ask Android to include them in global search are included too.
 */
final class Insight {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String SEARCHABLE = "android.app.searchable";
    private static final Map<String, Map<String, String>> EXTRAS = new ConcurrentHashMap<>();

    private Insight() {
    }

    /** SearchManager.semGetSearchablesInInsightSearch(boolean). */
    static List<SearchableInfo> searchables(SearchManager manager) {
        List<SearchableInfo> out = new ArrayList<>();
        Context c = App.context();
        if (c == null || manager == null) return out;
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), PackageManager.GET_META_DATA);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo r : found) {
            ActivityInfo a = r.activityInfo;
            if (a == null || a.metaData == null || !a.metaData.containsKey(SEARCHABLE)) continue;
            if (!a.enabled || !a.applicationInfo.enabled) continue;
            ComponentName name = new ComponentName(a.packageName, a.name);
            SearchableInfo info;
            try {
                info = manager.getSearchableInfo(name);
            } catch (RuntimeException e) {
                continue;
            }
            if (info == null || info.getSuggestAuthority() == null) continue;
            boolean insight = "true".equalsIgnoreCase(extras(pm, a).get("includeInInsightSearch"));
            if (insight || info.shouldIncludeInGlobalSearch()) out.add(info);
        }
        return out;
    }

    /**
     * SearchableInfo.semShouldIncludeInInsightSearch(): only for an app that made its provider for Finder, which then
     * speaks One UI's query dialect (search_suggest_regex_query); every other app gets Android's plain one.
     */
    static boolean included(SearchableInfo info) {
        return info != null && "true".equalsIgnoreCase(extra(info, "includeInInsightSearch"));
    }

    /** SearchableInfo.semGetSearchLayoutStyle(). */
    static String layoutStyle(SearchableInfo info) {
        return info == null ? null : extra(info, "searchLayoutStyle");
    }

    /**
     * SearchableInfo.semGetLabelId(): the searchable's own label (Android's hidden getLabelId, open to apps) for an app
     * that made it for Finder; for any other app its search screen's or its own name, since a searchable's label is
     * often a hint ("Searching…").
     */
    static int labelId(SearchableInfo info) {
        if (info == null) return 0;
        String insight = extra(info, "includeInInsightSearch");
        if (!"true".equalsIgnoreCase(insight)) {
            Context c = App.context();
            ComponentName activity = info.getSearchActivity();
            if (c != null && activity != null) {
                try {
                    ActivityInfo a = c.getPackageManager().getActivityInfo(activity, 0);
                    int label = a.labelRes != 0 ? a.labelRes : a.applicationInfo.labelRes;
                    if (label != 0) return label;
                } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                    // The searchable's own label, then.
                }
            }
        }
        try {
            return (Integer) SearchableInfo.class.getMethod("getLabelId").invoke(info);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static String extra(SearchableInfo info, String name) {
        ComponentName activity = info.getSearchActivity();
        if (activity == null) return null;
        Map<String, String> extras = EXTRAS.get(activity.flattenToShortString());
        if (extras == null) {
            Context c = App.context();
            if (c == null) return null;
            try {
                PackageManager pm = c.getPackageManager();
                extras = extras(pm, pm.getActivityInfo(activity, PackageManager.GET_META_DATA));
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return null;
            }
        }
        return extras.get(name);
    }

    /** The extra-attr name/value pairs of an activity's searchable XML (cached per activity). */
    private static Map<String, String> extras(PackageManager pm, ActivityInfo a) {
        String key = new ComponentName(a.packageName, a.name).flattenToShortString();
        Map<String, String> known = EXTRAS.get(key);
        if (known != null) return known;
        Map<String, String> extras = new HashMap<>();
        try (XmlResourceParser xml = a.loadXmlMetaData(pm, SEARCHABLE)) {
            if (xml != null) {
                for (int e = xml.next(); e != XmlResourceParser.END_DOCUMENT; e = xml.next()) {
                    if (e != XmlResourceParser.START_TAG || !"extra-attr".equals(xml.getName())) continue;
                    String name = xml.getAttributeValue(ANDROID, "name");
                    String value = xml.getAttributeValue(ANDROID, "value");
                    if (name != null) extras.put(name, value);
                }
            }
        } catch (Exception e) {
            extras = Collections.emptyMap();
        }
        EXTRAS.put(key, extras);
        return extras;
    }
}
