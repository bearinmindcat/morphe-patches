package org.ungoogled.honorcalendar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A time zone list standing in for MagicOS Settings' zone picker, which Calendar asks for an event's or its home time
 * zone (extra "activity_title"; answers the zone's ID in extra "id", as MagicOS's does).
 */
public class ZonePicker extends Activity {
    private static final class Zone implements Comparable<Zone> {
        final String id;
        final String label;
        final int offset;

        Zone(String id, long now) {
            TimeZone tz = TimeZone.getTimeZone(id);
            this.id = id;
            offset = tz.getOffset(now);
            int minutes = Math.abs(offset) / 60000;
            String gmt = String.format(Locale.ROOT, "GMT%s%02d:%02d", offset < 0 ? "-" : "+", minutes / 60, minutes % 60);
            String city = id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
            label = gmt + "  " + city + "  (" + tz.getDisplayName(tz.inDaylightTime(new java.util.Date(now)), TimeZone.LONG) + ")";
        }

        @Override
        public int compareTo(Zone o) {
            return offset != o.offset ? Integer.compare(offset, o.offset) : label.compareTo(o.label);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String title = getIntent().getStringExtra("activity_title");
        if (title != null) setTitle(title);
        long now = System.currentTimeMillis();
        List<Zone> zones = new ArrayList<>();
        for (String id : TimeZone.getAvailableIDs()) {
            if (id.indexOf('/') < 0 || id.startsWith("Etc/") || id.startsWith("SystemV/") || id.startsWith("US/")) continue;
            zones.add(new Zone(id, now));
        }
        Collections.sort(zones);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        EditText search = new EditText(this);
        search.setHint(android.R.string.search_go);
        search.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, pad / 2);
        root.addView(search, lp);
        ListView list = new ListView(this);
        ArrayAdapter<Zone> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, zones);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            Zone zone = (Zone) parent.getItemAtPosition(position);
            setResult(RESULT_OK, new Intent().putExtra("id", zone.id));
            finish();
        });
        setContentView(root);
    }
}
