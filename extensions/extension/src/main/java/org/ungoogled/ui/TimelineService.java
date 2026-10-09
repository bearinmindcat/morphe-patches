package org.ungoogled.ui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Timeline without a Google account: while switched on, a location-type
 * foreground service (with its notification) writes position fixes to a
 * private SQLite database. Started only from the foreground (the Timeline
 * screen, or Maps coming to the front while recording was left on), which is
 * what lets it keep receiving fixes without "allow all the time".
 */
public final class TimelineService extends Service implements LocationListener {
    static final String ACTION_START = "org.ungoogled.timeline.START";
    static final String ACTION_STOP = "org.ungoogled.timeline.STOP";
    static final String KEY_WANTED = "timeline_recording";
    private static final String CHANNEL = "ungoogled_timeline";
    private static final int NOTIF_ID = 0x544c;   // "TL"
    private static final long MIN_TIME_MS = 30_000L;
    private static final float MIN_DIST_M = 25f;

    private static volatile boolean running;
    static boolean isRunning() { return running; }

    private Db db;
    private LocationManager lm;

    /** Recording was left on but is not running (the phone restarted, or Android stopped it): start it again. */
    static void resumeIfWanted(Activity a) {
        try {
            if (running || !Shapes.timelinePatched() || !wanted(a) || !Screens.declared(a, TimelineService.class)) return;
            if (a.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            a.startForegroundService(new Intent(a, TimelineService.class).setAction(ACTION_START));
        } catch (Throwable ignored) {}
    }

    static boolean wanted(Context c) {
        return c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false);
    }

    static void setWanted(Context c, boolean on) {
        c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).commit();
    }

    @Override public void onCreate() {
        super.onCreate();
        db = new Db(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setWanted(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Timeline", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, Screens.intent(this, TimelineActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TimelineService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Recording your timeline")
                .setContentText("Kept only on this phone. Tap to view.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIF_ID, n);
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, MIN_TIME_MS, MIN_DIST_M, this, getMainLooper());
            }
            if (!running) db.sessionStart(System.currentTimeMillis());
            running = true;
            setWanted(this, true);
        } catch (SecurityException e) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override public void onLocationChanged(Location l) {
        // Network fixes are coarse; keep them only when nothing better is coming in.
        if (l.getAccuracy() > 200) return;
        db.insert(l.getTime(), l.getLatitude(), l.getLongitude(), l.getAccuracy());
    }

    @Override public void onDestroy() {
        if (running) db.sessionStop(System.currentTimeMillis());
        running = false;
        try { lm.removeUpdates(this); } catch (SecurityException ignored) {}
        db.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Raw fixes, in the app's private storage. */
    static final class Db extends SQLiteOpenHelper {
        static final class Point {
            final long ts;
            final double lat, lng;
            Point(long ts, double lat, double lng) { this.ts = ts; this.lat = lat; this.lng = lng; }
        }

        Db(Context c) { super(c.getApplicationContext(), "ungoogled_timeline.db", null, 2); }

        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE points (ts INTEGER PRIMARY KEY, lat REAL NOT NULL, lng REAL NOT NULL, acc REAL NOT NULL)");
            onUpgrade(d, 1, 2);
        }

        /** v2: recording sessions, so a stretch with recording off is never read as a visit. */
        @Override public void onUpgrade(SQLiteDatabase d, int a, int b) {
            if (a < 2) d.execSQL("CREATE TABLE sessions (start INTEGER NOT NULL, stop INTEGER)");
        }

        /** A new recording session; one left open by a killed process ends at its last fix. */
        void sessionStart(long now) {
            SQLiteDatabase w = getWritableDatabase();
            w.execSQL("UPDATE sessions SET stop = MAX(start, IFNULL((SELECT MAX(ts) FROM points WHERE ts >= sessions.start), start)) WHERE stop IS NULL");
            ContentValues v = new ContentValues(1);
            v.put("start", now);
            w.insert("sessions", null, v);
        }

        void sessionStop(long now) {
            getWritableDatabase().execSQL("UPDATE sessions SET stop = " + now + " WHERE stop IS NULL");
        }

        /** [start, stop] pairs, oldest first; stop is Long.MAX_VALUE while a session is still open. */
        List<long[]> sessions() {
            List<long[]> out = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery("SELECT start, IFNULL(stop, " + Long.MAX_VALUE + ") FROM sessions ORDER BY start ASC", null)) {
                while (c.moveToNext()) out.add(new long[]{c.getLong(0), c.getLong(1)});
            }
            return out;
        }

        void insert(long ts, double lat, double lng, float acc) {
            ContentValues v = new ContentValues(4);
            v.put("ts", ts); v.put("lat", lat); v.put("lng", lng); v.put("acc", acc);
            getWritableDatabase().insertWithOnConflict("points", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }

        List<Point> all() {
            List<Point> out = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, lat, lng FROM points ORDER BY ts ASC", null)) {
                while (c.moveToNext()) out.add(new Point(c.getLong(0), c.getDouble(1), c.getDouble(2)));
            }
            return out;
        }

        void clear() {
            getWritableDatabase().delete("points", null, null);
            getWritableDatabase().execSQL("DELETE FROM sessions WHERE stop IS NOT NULL");
        }
    }
}
