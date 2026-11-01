package org.ungoogled.honorclock;

import android.app.Activity;
import android.content.Intent;
import android.icu.text.TimeZoneNames;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The city picker Honor Clock's world clock opens to add a city (patches/.../honorclock sends its
 * "hihonor.intent.action.ZONE_PICKER" here). On an Honor phone that picker is MagicOS's Settings app, which other
 * phones don't have, so "+" did nothing. This one lists the phone's own time zones by city (Android's ICU names, in
 * the phone's language), with a search field, and answers as MagicOS's does: "id" (time zone), "name" (city) and
 * "unique_id" (the city's key, here the time zone id).
 */
public final class CityPicker extends Activity {
    private static final Set<String> REGIONS = new HashSet<>(java.util.Arrays.asList(
            "Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific"));

    private static final class City implements Comparable<City> {
        final String zone;
        final String name;
        final String label;

        City(String zone, String name, String label) {
            this.zone = zone;
            this.name = name;
            this.label = label;
        }

        @Override
        public int compareTo(City other) {
            return name.compareToIgnoreCase(other.name);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final List<City> all = new ArrayList<>();
    private ArrayAdapter<City> adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Locale locale = Locale.getDefault();
        TimeZoneNames names = TimeZoneNames.getInstance(locale);
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (String zone : TimeZone.getAvailableIDs()) {
            int slash = zone.indexOf('/');
            if (slash < 0 || !REGIONS.contains(zone.substring(0, slash))) continue;
            String canonical = android.icu.util.TimeZone.getCanonicalID(zone);
            if (canonical == null || !canonical.equals(zone) || !seen.add(zone)) continue;
            String name = names.getExemplarLocationName(zone);
            if (name == null || name.isEmpty()) name = zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ');
            int offset = TimeZone.getTimeZone(zone).getOffset(now) / 60000;
            String gmt = String.format(Locale.ROOT, "GMT%s%02d:%02d", offset < 0 ? "-" : "+", Math.abs(offset) / 60, Math.abs(offset) % 60);
            all.add(new City(zone, name, name + "  (" + gmt + ")"));
        }
        Collections.sort(all);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        int hint = getResources().getIdentifier("abc_search_hint", "string", getPackageName());
        if (hint != 0) search.setHint(hint);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(pad, pad / 2, pad, pad / 2);
        root.addView(search, searchParams);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(all));
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.setFitsSystemWindows(true);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.getDefault());
                adapter.clear();
                for (City city : all) {
                    if (query.isEmpty() || city.name.toLowerCase(Locale.getDefault()).contains(query)
                            || city.zone.toLowerCase(Locale.ROOT).contains(query)) adapter.add(city);
                }
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                City city = adapter.getItem(position);
                if (city == null) return;
                setResult(RESULT_OK, new Intent()
                        .putExtra("id", city.zone)
                        .putExtra("name", city.name)
                        .putExtra("unique_id", city.zone));
                finish();
            }
        });
    }
}
