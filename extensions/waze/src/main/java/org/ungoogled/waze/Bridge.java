package org.ungoogled.waze;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands Waze's alerts to Ungoogled Maps on the same phone.
 *
 * Waze's native side keeps a repository of the alerts it is about to warn about -- police, crashes,
 * hazards, jams, closures, cameras -- around the car and along its route, and hands every change to
 * Java through AlerterNativeManager.updateAlertersRepository(NativeAlertRepositoryUpdate). The patch
 * calls alerts() from there with that update; this reads each alert's type, title, position and
 * trigger distance and broadcasts them on the phone (org.ungoogled.waze.ALERTS, plain intent extras)
 * to whichever app is listening -- Ungoogled Maps with Waze alerts turned on draws them on its map.
 * Nothing leaves the phone. While there are alerts the same list goes out again every minute, so
 * Maps knows Waze is still running; once Waze is gone, Maps drops them a few minutes later.
 *
 * The JNI proto classes keep their names from one Waze build to the next (the native side looks
 * them up by name), so their getters are called by name here rather than compiled against.
 */
public final class Bridge {
    private Bridge() {}

    /**
     * The broadcast's extras, one entry per alert: int[] type (Waze's AlerterType number: 1 police,
     * 2 crash, 3 jam, 5 hazard, 10 camera, 12 closure, ...), subType, lat and lng (degrees times a
     * million), trigger (metres ahead at which Waze would warn), thumbs, report; String[] id, title,
     * description, icon; plus long at (the time) and String from (Waze's package name).
     */
    public static final String ACTION = "org.ungoogled.waze.ALERTS";
    /**
     * Only after `adb shell setprop log.tag.UA DEBUG` and a restart of Waze: a broadcast of this
     * action with double lat, lng (and int type, count, String title, boolean real) fakes a list of
     * alerts through alerts() -- `real` through AlerterNativeManager itself -- to test the link to
     * Maps without a drive. Never on without that property.
     */
    static final String ACTION_TEST = "org.ungoogled.waze.SELF_TEST";
    private static final long HEARTBEAT_MS = 60_000L;
    private static final long SAME_AGAIN_MS = 20_000L;

    private static volatile Context app;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Method> METHODS = new HashMap<>();

    private static Intent last;
    private static String lastKey = "";
    private static long lastSentAt;
    private static boolean beating;

    /** From WazeMobileApplication.onCreate. */
    public static void init(Application a) {
        try {
            app = a;
            Log.i("UA", "Waze alerts bridge ready");
            if (Log.isLoggable("UA", Log.DEBUG)) {
                IntentFilter f = new IntentFilter(ACTION_TEST);
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(new SelfTest(), f, Context.RECEIVER_EXPORTED);
                else a.registerReceiver(new SelfTest(), f);
                Log.d("UA", "Waze alerts bridge: self-test receiver on, " + ACTION_TEST);
            }
        } catch (Throwable t) {
            Log.w("UA", "Waze alerts bridge", t);
        }
    }

    /** From AlerterNativeManager.updateAlertersRepository: [update] is Waze's NativeAlertRepositoryUpdate. */
    public static void alerts(Object update) {
        try {
            if (update == null || !Customization.on("shareAlerts")) return;
            Object descriptors = call(update, "getDescriptorsList");
            int n = descriptors instanceof List ? ((List<?>) descriptors).size() : 0;
            int[] type = new int[n], sub = new int[n], lat = new int[n], lng = new int[n], trigger = new int[n],
                    thumbs = new int[n], report = new int[n];
            String[] id = new String[n], title = new String[n], description = new String[n], icon = new String[n];
            StringBuilder key = new StringBuilder();
            int k = 0;
            for (int i = 0; i < n; i++) {
                Object d = ((List<?>) descriptors).get(i);
                Object info = call(d, "getInfo");
                Object position = call(info, "getPosition");
                int la = (Integer) call(position, "getLatitude"), ln = (Integer) call(position, "getLongitude");
                if (la == 0 && ln == 0) continue;
                type[k] = (Integer) call(info, "getTypeValue");
                sub[k] = (Integer) call(info, "getSubType");
                lat[k] = la;
                lng[k] = ln;
                trigger[k] = (Integer) call(d, "getTriggerDistanceMeters");
                thumbs[k] = (Integer) call(info, "getNumThumbsUp");
                report[k] = (Integer) call(info, "getReportId");
                Object alertId = call(d, "getAlertId");
                String uid = text(alertId, "getUniqueId");
                if (uid.isEmpty()) uid = text(alertId, "getUuid");
                if (uid.isEmpty()) uid = report[k] != 0 ? "r" + report[k] : la + "," + ln + "," + type[k];
                id[k] = uid;
                title[k] = text(info, "getTitle");
                description[k] = text(info, "getDescription");
                icon[k] = text(info, "getIconName");
                key.append(uid).append('|').append(type[k]).append('|').append(la).append(',').append(ln).append(';');
                k++;
            }
            Context c = context();
            Intent i = new Intent(ACTION)
                    .putExtra("type", Arrays.copyOf(type, k))
                    .putExtra("subType", Arrays.copyOf(sub, k))
                    .putExtra("lat", Arrays.copyOf(lat, k))
                    .putExtra("lng", Arrays.copyOf(lng, k))
                    .putExtra("trigger", Arrays.copyOf(trigger, k))
                    .putExtra("thumbs", Arrays.copyOf(thumbs, k))
                    .putExtra("report", Arrays.copyOf(report, k))
                    .putExtra("id", Arrays.copyOf(id, k))
                    .putExtra("title", Arrays.copyOf(title, k))
                    .putExtra("description", Arrays.copyOf(description, k))
                    .putExtra("icon", Arrays.copyOf(icon, k))
                    .putExtra("at", System.currentTimeMillis())
                    .putExtra("from", c != null ? c.getPackageName() : "com.waze")
                    // Foreground queue, and no skipping a Maps the system still counts as stopped: only
                    // a running Maps has a receiver anyway, so nothing is woken up.
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            send(i, key.toString(), k);
        } catch (Throwable t) {
            Log.w("UA", "Waze alerts bridge", t);
        }
    }

    /** Sends [i] unless the same list went out in the last 20 s; a list with alerts is sent again every minute. */
    private static void send(Intent i, String key, int count) {
        long now = SystemClock.elapsedRealtime();
        synchronized (Bridge.class) {
            if (key.equals(lastKey) && now - lastSentAt < SAME_AGAIN_MS) return;
            last = i;
            lastKey = key;
            lastSentAt = now;
            if (count > 0 && !beating) {
                beating = true;
                MAIN.postDelayed(BEAT, HEARTBEAT_MS);
            }
        }
        broadcast(i, count);
    }

    private static final Runnable BEAT = new Runnable() {
        @Override
        public void run() {
            Intent again;
            int count;
            synchronized (Bridge.class) {
                beating = false;
                int[] type = last == null ? null : last.getIntArrayExtra("type");
                count = type == null ? 0 : type.length;
                if (count == 0) return;
                again = new Intent(last).putExtra("at", System.currentTimeMillis());
                last = again;
                lastSentAt = SystemClock.elapsedRealtime();
                beating = true;
                MAIN.postDelayed(this, HEARTBEAT_MS);
            }
            broadcast(again, count);
        }
    };

    private static void broadcast(Intent i, int count) {
        Context c = context();
        if (c == null) {
            Log.w("UA", "Waze alerts bridge: no context yet");
            return;
        }
        c.sendBroadcast(i);
        Log.i("UA", "Waze alerts shared: " + count);
    }

    private static Context context() {
        Context c = app;
        if (c != null) return c;
        try {
            c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
        if (c != null) app = c;
        return c;
    }

    // ---- the proto getters, by name -------------------------------------------------------

    private static Object call(Object o, String name) throws Exception {
        Class<?> cls = o.getClass();
        String key = cls.getName() + '#' + name;
        Method m;
        synchronized (METHODS) {
            m = METHODS.get(key);
        }
        if (m == null) {
            m = cls.getMethod(name);
            synchronized (METHODS) {
                METHODS.put(key, m);
            }
        }
        return m.invoke(o);
    }

    private static String text(Object o, String name) throws Exception {
        Object v = call(o, name);
        return v == null ? "" : v.toString();
    }

    // ---- self-test ----------------------------------------------------------------------

    /**
     * Builds a repository update with Waze's own proto builders -- the same classes alerts() reads --
     * from a test broadcast's lat, lng, type, count and title, and hands it to alerts(), or with
     * `real` to AlerterNativeManager.updateAlertersRepository itself, the patched method.
     */
    private static final class SelfTest extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent in) {
            try {
                double lat = in.getDoubleExtra("lat", 0), lng = in.getDoubleExtra("lng", 0);
                int type = in.getIntExtra("type", 1), count = Math.max(1, Math.min(20, in.getIntExtra("count", 1)));
                String title = in.getStringExtra("title");
                if (title == null) title = "TEST alert from the Waze bridge";
                Class<?> positionClass = Class.forName("com.waze.jni.protos.Position$IntPosition");
                Class<?> infoClass = Class.forName("com.waze.jni.protos.alerters.AlerterInfo");
                Class<?> idClass = Class.forName("com.waze.jni.protos.alerters.AlerterId");
                Class<?> descriptorClass = Class.forName("com.waze.jni.protos.alerters.NativeAlertDescriptor");
                Class<?> updateClass = Class.forName("com.waze.jni.protos.alerters.NativeAlertRepositoryUpdate");
                Object update = updateClass.getMethod("newBuilder").invoke(null);
                for (int k = 0; k < count; k++) {
                    Object position = positionClass.getMethod("newBuilder").invoke(null);
                    set(position, "setLatitude", int.class, (int) Math.round((lat + 0.002 * k) * 1e6));
                    set(position, "setLongitude", int.class, (int) Math.round(lng * 1e6));
                    Object info = infoClass.getMethod("newBuilder").invoke(null);
                    set(info, "setTypeValue", int.class, type);
                    set(info, "setTitle", String.class, count > 1 ? title + " " + (k + 1) : title);
                    set(info, "setDescription", String.class, "Self-test of the Waze alerts bridge");
                    set(info, "setPosition", positionClass, build(position));
                    set(info, "setReportId", int.class, 424242 + k);
                    Object id = idClass.getMethod("newBuilder").invoke(null);
                    set(id, "setUniqueId", String.class, "ua-test-" + k);
                    Object descriptor = descriptorClass.getMethod("newBuilder").invoke(null);
                    set(descriptor, "setInfo", infoClass, build(info));
                    set(descriptor, "setAlertId", idClass, build(id));
                    set(descriptor, "setTriggerDistanceMeters", int.class, 500);
                    set(update, "addDescriptors", descriptorClass, build(descriptor));
                }
                Object built = build(update);
                if (in.getBooleanExtra("real", false)) {
                    Class<?> manager = Class.forName("com.waze.alerters.AlerterNativeManager");
                    Object instance = manager.getMethod("getInstance").invoke(null);
                    Method m = manager.getDeclaredMethod("updateAlertersRepository", updateClass);
                    m.setAccessible(true);
                    m.invoke(instance, built);
                    Log.d("UA", "Waze alerts bridge: self-test through AlerterNativeManager, " + count + " alerts");
                } else {
                    synchronized (Bridge.class) {
                        lastKey = "";   // so the same test list goes out again at once
                    }
                    alerts(built);
                    Log.d("UA", "Waze alerts bridge: self-test, " + count + " alerts");
                }
            } catch (Throwable t) {
                Log.w("UA", "Waze alerts bridge: self-test", t);
            }
        }

        private static void set(Object builder, String name, Class<?> type, Object value) throws Exception {
            builder.getClass().getMethod(name, type).invoke(builder, value);
        }

        private static Object build(Object builder) throws Exception {
            return builder.getClass().getMethod("build").invoke(builder);
        }
    }
}
