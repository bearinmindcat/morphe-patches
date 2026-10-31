package org.ungoogled.samsungroutines;

import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What One UI's event delegation service does for the app: watch broadcasts (charging, battery level, Wi-Fi, headset,
 * time changes, ...) and content URIs, and fire the app's PendingIntent when one arrives. The routine conditions are
 * built on it; off One UI nothing watched and no automatic routine ever started.
 *
 * Registrations are stored in a file, as One UI keeps them across restarts and the app registers each one only once.
 * The app's main process (the routine engine, kept running by EngineHost) holds the live receivers and brings the file's
 * registrations back whenever it starts; the screens' process only writes the file and asks the engine to catch up.
 */
public final class EventDelegation {
    private static final String TAG = "UA";
    private static final String FILE = "ungoogled_event_delegation.json";
    static final String ACTION_SYNC = "org.ungoogled.samsungroutines.SYNC_EVENT_DELEGATION";

    /** Live registrations in the engine process, by key. */
    private static final Map<String, Object> LIVE = new HashMap<>();
    private static Handler main;

    private EventDelegation() {
    }

    // Registering

    public static void register(Context c, IntentFilter filter, PendingIntent pi) {
        String recipe = PendingIntents.recipe(pi);
        if (filter == null || recipe == null) {
            Log.w(TAG, "event delegation: cannot keep " + pi);
            return;
        }
        try {
            JSONObject entry = new JSONObject();
            entry.put("kind", "filter");
            entry.put("filter", filterJson(filter));
            entry.put("pi", recipe);
            edit(c, key("filter", filterJson(filter).toString(), recipe), entry);
        } catch (JSONException e) {
            Log.w(TAG, "event delegation", e);
        }
    }

    public static void unregister(Context c, IntentFilter filter, PendingIntent pi) {
        String recipe = PendingIntents.recipe(pi);
        if (filter == null || recipe == null) return;
        try {
            edit(c, key("filter", filterJson(filter).toString(), recipe), null);
        } catch (JSONException e) {
            Log.w(TAG, "event delegation", e);
        }
    }

    public static void register(Context c, Uri uri, PendingIntent pi) {
        String recipe = PendingIntents.recipe(pi);
        if (uri == null || recipe == null) return;
        try {
            JSONObject entry = new JSONObject();
            entry.put("kind", "uri");
            entry.put("uri", uri.toString());
            entry.put("pi", recipe);
            edit(c, key("uri", uri.toString(), recipe), entry);
        } catch (JSONException e) {
            Log.w(TAG, "event delegation", e);
        }
    }

    public static void unregister(Context c, Uri uri, PendingIntent pi) {
        String recipe = PendingIntents.recipe(pi);
        if (uri == null || recipe == null) return;
        edit(c, key("uri", uri.toString(), recipe), null);
    }

    public static void unregisterAll(Context c) {
        edit(c, null, null);
    }

    private static String key(String kind, String what, String recipe) {
        return kind + "\n" + what + "\n" + recipe;
    }

    private static JSONObject filterJson(IntentFilter f) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray actions = new JSONArray();
        for (int i = 0; i < f.countActions(); i++) actions.put(f.getAction(i));
        o.put("actions", actions);
        JSONArray categories = new JSONArray();
        for (int i = 0; i < f.countCategories(); i++) categories.put(f.getCategory(i));
        o.put("categories", categories);
        JSONArray schemes = new JSONArray();
        for (int i = 0; i < f.countDataSchemes(); i++) schemes.put(f.getDataScheme(i));
        o.put("schemes", schemes);
        JSONArray types = new JSONArray();
        for (int i = 0; i < f.countDataTypes(); i++) types.put(f.getDataType(i));
        o.put("types", types);
        o.put("priority", f.getPriority());
        return o;
    }

    private static IntentFilter filterOf(JSONObject o) throws JSONException {
        IntentFilter f = new IntentFilter();
        JSONArray a = o.getJSONArray("actions");
        for (int i = 0; i < a.length(); i++) f.addAction(a.getString(i));
        a = o.getJSONArray("categories");
        for (int i = 0; i < a.length(); i++) f.addCategory(a.getString(i));
        a = o.getJSONArray("schemes");
        for (int i = 0; i < a.length(); i++) f.addDataScheme(a.getString(i));
        a = o.getJSONArray("types");
        for (int i = 0; i < a.length(); i++) {
            try {
                f.addDataType(a.getString(i));
            } catch (IntentFilter.MalformedMimeTypeException e) {
                // Skipped.
            }
        }
        f.setPriority(o.optInt("priority"));
        return f;
    }

    // The registry file, shared by the app's processes

    private static File file(Context c) {
        return new File(c.getNoBackupFilesDir(), FILE);
    }

    /** Adds (entry != null), removes (entry == null) or, with key == null, clears; then the engine catches up. */
    private static void edit(Context c, String key, JSONObject entry) {
        File f = file(c);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(f.getPath() + ".lock"), "rw");
             FileLock lock = lockFile.getChannel().lock()) {
            JSONObject all = read(f);
            if (key == null) {
                all = new JSONObject();
            } else if (entry == null) {
                all.remove(key);
            } else {
                all.put(key, entry);
            }
            AtomicFile atomic = new AtomicFile(f);
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (IOException e) {
                atomic.failWrite(out);
                throw e;
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "event delegation: could not save", e);
            return;
        }
        if (isEngine(c)) {
            sync(c);
        } else {
            try {
                c.startService(new Intent(c, EngineHost.class).setAction(ACTION_SYNC));
            } catch (RuntimeException e) {
                Log.w(TAG, "event delegation: engine not reachable now, it catches up when it starts", e);
            }
        }
    }

    private static JSONObject read(File f) {
        try {
            byte[] bytes = new AtomicFile(f).readFully();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    // The engine process

    static boolean isEngine(Context c) {
        return c.getPackageName().equals(Application.getProcessName());
    }

    /** In the engine process: brings the live receivers and observers in line with the file. */
    static synchronized void sync(Context context) {
        if (!isEngine(context)) return;
        final Context c = context.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        JSONObject all = read(file(c));
        for (Iterator<Map.Entry<String, Object>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> live = it.next();
            if (all.has(live.getKey())) continue;
            Object o = live.getValue();
            try {
                if (o instanceof BroadcastReceiver) c.unregisterReceiver((BroadcastReceiver) o);
                else if (o instanceof ContentObserver) c.getContentResolver().unregisterContentObserver((ContentObserver) o);
            } catch (RuntimeException e) {
                // Already gone.
            }
            it.remove();
        }
        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (LIVE.containsKey(key)) continue;
            try {
                JSONObject entry = all.getJSONObject(key);
                final PendingIntent pi = PendingIntents.make(c, entry.getString("pi"));
                if (pi == null) continue;
                if ("filter".equals(entry.getString("kind"))) {
                    BroadcastReceiver receiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            // One UI reports changes, not the current sticky state at registration.
                            if (isInitialStickyBroadcast()) return;
                            fire(c, pi, intent);
                        }
                    };
                    c.registerReceiver(receiver, filterOf(entry.getJSONObject("filter")), null, main, Context.RECEIVER_EXPORTED);
                    LIVE.put(key, receiver);
                } else {
                    ContentObserver observer = new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            fire(c, pi, null);
                        }
                    };
                    c.getContentResolver().registerContentObserver(Uri.parse(entry.getString("uri")), true, observer);
                    LIVE.put(key, observer);
                }
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "event delegation: could not register " + key, e);
            }
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "event delegation: " + LIVE.size() + " live");
    }

    private static void fire(Context c, PendingIntent pi, Intent event) {
        try {
            pi.send(c, 0, event);
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "event delegation: could not deliver " + (event != null ? event.getAction() : "change"), e);
        }
    }
}
