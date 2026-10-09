package org.ungoogled.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where this phone has been, grouped by day: visits (at least five minutes
 * within 75 m), named after a saved place, Home or Work when one is close, and
 * the travel between them. Tapping a visit opens the spot on the map.
 */
public final class TimelineActivity extends UiScreen {
    private static final float STAY_RADIUS_M = 75f, NEAR_SAVED_M = 150f;
    private static final long STAY_MIN_MS = 5 * 60_000L;
    /** Longest a single sighting may stand for: a gap longer than this is a recording gap, not a visit. */
    private static final long MAX_HOLD_MS = 12 * 3_600_000L;
    private static final int PERMISSIONS = 1, EXPORT_GPX = 2;
    private TimelineService.Db db;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new TimelineService.Db(this);
        frame("Timeline");
    }

    @Override protected void onResume() {
        super.onResume();
        SavedStore.load(this);
        render();
    }

    @Override protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private void render() {
        body.removeAllViews();
        body.addView(hint("A private record of where this phone has been. It stays in this app on this phone: "
                + "no account, nothing uploaded. Recording shows a notification and uses some battery."));

        // A root mount install keeps stock Maps' manifest, so the recorder service does not exist there.
        boolean recorder = Screens.declared(this, TimelineService.class);
        LinearLayout record = rowBase("Record my timeline",
                !recorder ? "Not on a root mount install" : TimelineService.isRunning() ? "On" : "Off");
        Switch sw = new Switch(this);
        sw.setChecked(TimelineService.isRunning());
        sw.setEnabled(recorder);
        record.setOrientation(LinearLayout.HORIZONTAL);
        // rowBase stacks title over summary; put them in a column so the switch can sit on the right
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        while (record.getChildCount() > 0) { android.view.View c = record.getChildAt(0); record.removeViewAt(0); col.addView(c); }
        record.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        record.addView(sw);
        if (recorder) record.setOnClickListener(v -> sw.toggle());
        sw.setOnCheckedChangeListener((b, on) -> { if (on) start(); else stop(); });
        body.addView(record);
        if (!recorder) {
            body.addView(hint("Recording runs as a background service, and a root mount install can only use the ones "
                    + "stock Maps declares. Install the patched app normally to record."));
        }

        List<TimelineService.Db.Point> pts = db.all();
        body.addView(action("Export GPX", pts.size() + " points recorded", v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gpx+xml").putExtra(Intent.EXTRA_TITLE, "ungoogled-maps-timeline.gpx");
            startActivityForResult(i, EXPORT_GPX);
        }));
        body.addView(action("Delete timeline", "Removes everything recorded", v -> new AlertDialog.Builder(this, SavedPlaces.dialogTheme(this))
                .setTitle("Delete your timeline?")
                .setMessage("Everything recorded on this phone is removed.")
                .setPositiveButton("Delete", (d, w) -> { db.clear(); render(); })
                .setNegativeButton("Cancel", null)
                .show()));

        List<Entry> entries = group(pts, db.sessions(), System.currentTimeMillis(), TimelineService.isRunning());
        if (entries.isEmpty()) {
            body.addView(section("Your days"));
            body.addView(hint(recorder ? "Nothing recorded yet. Switch on Record my timeline." : "Nothing recorded yet."));
            return;
        }
        String day = null;
        for (int k = entries.size() - 1; k >= 0; k--) {
            Entry e = entries.get(k);
            String d = dayOf(e.start);
            if (!d.equals(day)) { body.addView(section(d)); day = d; }
            if (e.stay) {
                LinearLayout r = rowBase(timeOf(e.start) + " – " + timeOf(e.end) + "  ·  " + duration(e.end - e.start), label(e.lat, e.lng));
                r.setOnClickListener(v -> openSpot(e.lat, e.lng, label(e.lat, e.lng)));
                body.addView(r);
            } else {
                LinearLayout r = rowBase(String.format(Locale.getDefault(), "On the move  ·  %.1f km", e.meters / 1000f),
                        timeOf(e.start) + " – " + timeOf(e.end));
                r.setOnClickListener(v -> openSpot(e.lat, e.lng, "On the move"));
                body.addView(r);
            }
        }
    }

    // ---- recording ------------------------------------------------------------------

    private void start() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), PERMISSIONS); return; }
        startForegroundService(new Intent(this, TimelineService.class).setAction(TimelineService.ACTION_START));
        body.postDelayed(this::render, 400);
    }

    private void stop() {
        TimelineService.setWanted(this, false);
        startService(new Intent(this, TimelineService.class).setAction(TimelineService.ACTION_STOP));
        body.postDelayed(this::render, 400);
    }

    @Override public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        if (request == PERMISSIONS && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) start();
        else render();
    }

    // ---- days, visits and moves --------------------------------------------------------

    private static final class Entry {
        boolean stay;
        long start, end;
        double lat, lng;
        float meters;
    }

    /**
     * Fixes within STAY_RADIUS_M of a first one are one place; the phone counts as there until it
     * is next seen elsewhere -- the recorder only reports after 25 m of movement, so sitting still
     * produces no fixes at all -- but never past the end of its recording session (or now), and
     * never more than MAX_HOLD_MS. At least STAY_MIN_MS there is a visit; everything else is
     * travel, merged into one stretch between visits.
     */
    static List<Entry> group(List<TimelineService.Db.Point> pts, List<long[]> sessions, long now, boolean recording) {
        List<Entry> out = new ArrayList<>();
        int i = 0;
        while (i < pts.size()) {
            TimelineService.Db.Point first = pts.get(i);
            int j = i + 1;
            while (j < pts.size() && dist(first, pts.get(j)) <= STAY_RADIUS_M) j++;
            TimelineService.Db.Point last = pts.get(j - 1);
            long seenUntil = j < pts.size() ? pts.get(j).ts : now;
            long end = Math.min(Math.min(seenUntil, sessionEnd(sessions, last.ts, now, recording)), last.ts + MAX_HOLD_MS);
            if (end - first.ts >= STAY_MIN_MS) {
                Entry e = new Entry();
                e.stay = true; e.start = first.ts; e.end = end;
                for (int k = i; k < j; k++) { e.lat += pts.get(k).lat; e.lng += pts.get(k).lng; }
                e.lat /= (j - i); e.lng /= (j - i);
                out.add(e);
            } else {
                Entry move = out.isEmpty() || out.get(out.size() - 1).stay ? null : out.get(out.size() - 1);
                if (move == null) {
                    move = new Entry();
                    move.start = first.ts; move.lat = first.lat; move.lng = first.lng;
                    out.add(move);
                }
                for (int k = i; k < j; k++) {
                    TimelineService.Db.Point p = pts.get(k);
                    if (k > 0) move.meters += dist(pts.get(k - 1), p);
                    move.end = p.ts;
                }
            }
            i = j;
        }
        return out;
    }

    /**
     * When recording stopped after [ts]: its session's stop; for a session still open, now while
     * recording runs, else its last fix (the recorder was killed without a clean stop). Fixes
     * outside every session are single moments.
     */
    private static long sessionEnd(List<long[]> sessions, long ts, long now, boolean recording) {
        for (long[] s : sessions) {
            if (s[0] <= ts && ts <= s[1]) return s[1] != Long.MAX_VALUE ? s[1] : recording ? now : ts;
        }
        return ts;
    }

    private static float dist(TimelineService.Db.Point a, TimelineService.Db.Point b) {
        float[] out = new float[1];
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, out);
        return out[0];
    }

    /** "Home", "Work" or a saved place's name when one is close by, else the coordinates. */
    private String label(double lat, double lng) {
        String best = null;
        float bestM = NEAR_SAVED_M;
        float[] out = new float[1];
        List<Object[]> candidates = new ArrayList<>();
        if (SavedStore.home != null) candidates.add(new Object[]{"Home", SavedStore.home});
        if (SavedStore.work != null) candidates.add(new Object[]{"Work", SavedStore.work});
        for (SavedStore.Place p : SavedStore.places.values()) candidates.add(new Object[]{p.name, p});
        for (Object[] c : candidates) {
            SavedStore.Place p = (SavedStore.Place) c[1];
            Location.distanceBetween(lat, lng, p.lat, p.lng, out);
            if (out[0] < bestM) { bestM = out[0]; best = (String) c[0]; }
        }
        return best != null ? best : String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    private void openSpot(double lat, double lng, String name) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)",
                lat, lng, lat, lng, Uri.encode(name)))).setPackage(getPackageName());
        startActivity(i);
    }

    private String dayOf(long ts) {
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(ts);
        return DateFormat.format(c.get(Calendar.YEAR) == year ? "EEEE, MMM d" : "EEEE, MMM d yyyy", c).toString();
    }

    private String timeOf(long ts) {
        return DateFormat.getTimeFormat(this).format(new Date(ts));
    }

    private static String duration(long ms) {
        long min = Math.max(1, ms / 60_000L);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    // ---- GPX ----------------------------------------------------------------------------

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_GPX || result != RESULT_OK || data == null || data.getData() == null) return;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder g = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"Ungoogled Maps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "<trk><name>Ungoogled Maps timeline</name><trkseg>\n");
        for (TimelineService.Db.Point p : db.all()) {
            g.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n", p.lat, p.lng, iso.format(new Date(p.ts))));
        }
        g.append("</trkseg></trk></gpx>\n");
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new java.io.IOException("cannot write");
            out.write(g.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "That didn't work: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
