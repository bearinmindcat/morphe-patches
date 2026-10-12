package org.ungoogled.waze;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Customization row at the bottom of Waze's side menu, under Shut off, and the screen it opens:
 * a switch for each patched feature that can be turned on and off in the app.
 *
 * Most of the features are Waze configuration values the patches keep (Hide ads, Alert distances, ...).
 * Waze's server pushes those values; the patches set and lock them after every sync, through
 * keep*() from ConfigManager.uaOverrides. A feature switched off here is left to Waze instead: its
 * values are unlocked and put back to Waze's own default, and the server's value returns at the next
 * sync. Switching takes effect at once, on Waze's native thread, as Waze's own settings do.
 */
public final class Customization {
    private Customization() {}

    private static final String PREFS = "ungoogled_waze";

    /** Feature key -> {title, summary}, in the order the screen lists them. */
    private static final Map<String, String[]> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("hideAds", new String[]{"Hide ads",
                "Sponsored pins on the map and on Android Auto, ads while you are stopped, and ad statistics"});
        LABELS.put("noAssistant", new String[]{"No Google Assistant",
                "No Assistant button, onboarding or settings entry"});
        LABELS.put("noAiReporting", new String[]{"No AI reporting",
                "Conversational reporting (Gemini) off, with its dialogs, tips and onboarding"});
        LABELS.put("allMoods", new String[]{"All moods",
                "Every mood can be chosen, including the beta ones"});
        LABELS.put("reportSpeedLimit", new String[]{"Report speed limit",
                "Waze's switch for correcting the speed limit from the speedometer"});
        LABELS.put("camerasEverywhere", new String[]{"Speed cameras everywhere",
                "Speed camera and police alerts where Waze's server turns them off"});
        LABELS.put("cameraSoundAnySpeed", new String[]{"Speed camera sound at any speed",
                "The camera sound for every camera, not only when you are speeding"});
        LABELS.put("alertDistances", new String[]{"Alert distances",
                "How far ahead police, hazards, crashes, jams and cameras are announced (the patch's values)"});
        LABELS.put("navigationDistances", new String[]{"Navigation distances",
                "Android Auto heads-up distances and the traffic bar threshold (the patch's values)"});
        LABELS.put("speedLimitSign", new String[]{"Speed limit sign",
                "The speed limit sign style the patch was set to, whatever the country"});
        LABELS.put("shareAlerts", new String[]{"Share alerts with Maps",
                "The police, crashes, hazards and other alerts Waze is tracking, for Ungoogled Maps on this phone (Waze alerts in its Waze Features)"});
        LABELS.put("stayAwake", new String[]{"Stay awake in the background",
                "Waze keeps running longer without a route, so its alerts keep reaching Maps"});
    }

    /** The features the patches applied, as declared by the patch-generated uaDeclared(). */
    private static final Set<String> declared = new LinkedHashSet<>();
    private static boolean declaredLoaded;

    /** One kept configuration value. */
    private static final class Kept {
        final String name;
        final int id;
        final Object value;   // Boolean, Integer or String

        Kept(String name, int id, Object value) {
            this.name = name; this.id = id; this.value = value;
        }
    }

    private static volatile Context app;
    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    // ---- start-up -------------------------------------------------------------------------

    /** From WazeMobileApplication.onCreate. */
    public static void init(Application a) {
        app = a;
        try {
            a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity x) { resumed = new WeakReference<>(x); }
                @Override public void onActivityPaused(Activity x) {}
                @Override public void onActivityCreated(Activity x, Bundle b) {}
                @Override public void onActivityStarted(Activity x) {}
                @Override public void onActivityStopped(Activity x) {}
                @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
                @Override public void onActivityDestroyed(Activity x) {}
            });
        } catch (Throwable t) {
            Log.w("UA", "Waze customization", t);
        }
    }

    private static Context context() {
        Context c = app;
        if (c != null) return c;
        try {
            c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
        app = c;
        return c;
    }

    /** Called by the patch-generated uaDeclared(), once per applied feature. */
    public static void feature(String key) {
        synchronized (declared) {
            declared.add(key);
        }
    }

    private static List<String> features() {
        synchronized (declared) {
            if (!declaredLoaded) {
                declaredLoaded = true;
                try {
                    Method m = Customization.class.getDeclaredMethod("uaDeclared");
                    m.setAccessible(true);
                    m.invoke(null);
                } catch (Throwable t) {
                    Log.w("UA", "Waze customization: no features declared", t);
                }
            }
            List<String> out = new ArrayList<>();
            for (String k : LABELS.keySet()) if (declared.contains(k)) out.add(k);
            for (String k : declared) if (!out.contains(k)) out.add(k);
            return out;
        }
    }

    // ---- settings ---------------------------------------------------------------------------

    private static SharedPreferences prefs() {
        Context c = context();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether [feature] is switched on; every patched feature starts on. */
    public static boolean on(String feature) {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean(feature, true);
    }

    private static void set(String feature, boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(feature, on).commit();
        // Every kept value again, each by its feature's switch: ConfigManager.uaOverrides, the patch's
        // method, on Waze's native thread -- where Waze's own settings run their native half too.
        Runnable apply = () -> {
            try {
                Class.forName("com.waze.ConfigManager").getMethod("uaOverrides").invoke(null);
                Log.i("UA", "Waze customization: " + feature + (on ? " on" : " off"));
            } catch (Throwable t) {
                Log.w("UA", "Waze customization: uaOverrides", t);
            }
        };
        try {
            Method post = Class.forName("com.waze.NativeManager").getMethod("Post", Runnable.class);
            post.invoke(null, apply);
        } catch (Throwable t) {
            Log.w("UA", "Waze customization: NativeManager.Post", t);
        }
    }

    // ---- the configuration values -------------------------------------------------------------

    /** From ConfigManager.uaOverrides, after every sync: keep [value] for [feature] when it is on. */
    public static void keepBool(String feature, String name, int id, boolean value) { keep(feature, name, id, value); }
    public static void keepInt(String feature, String name, int id, int value) { keep(feature, name, id, value); }
    public static void keepString(String feature, String name, int id, String value) { keep(feature, name, id, value); }

    private static void keep(String feature, String name, int id, Object value) {
        try {
            apply(feature, new Kept(name, id, value));
        } catch (Throwable t) {
            Log.w("UA", "Waze customization: " + name, t);
        }
    }

    private static void apply(String feature, Kept k) throws Exception {
        Object cm = Class.forName("com.waze.ConfigManager").getMethod("getInstance").invoke(null);
        Class<?> c = cm.getClass();
        Method lock = c.getMethod("setConfigValueLockedNTV", int.class, boolean.class);
        if (on(feature)) {
            // Unlocked, set, locked again: Waze's native config refuses a server update to a locked value.
            lock.invoke(cm, k.id, false);
            write(cm, k.id, k.value);
            lock.invoke(cm, k.id, true);
        } else {
            boolean locked = (Boolean) c.getMethod("isConfigValueLockedNTV", int.class).invoke(cm, k.id);
            if (!locked) {
                Log.d("UA", "Waze customization: " + k.name + " already Waze's own: " + read(cm, k));
                return;
            }
            lock.invoke(cm, k.id, false);
            Object stock = wazeDefault(k.name);
            if (stock != null) write(cm, k.id, convert(stock, k.value));
            Log.d("UA", "Waze customization: " + k.name + " given back, default " + stock + ", now " + read(cm, k));
        }
    }

    /** The value Waze's native config holds now for [k]. */
    private static Object read(Object cm, Kept k) {
        try {
            Class<?> c = cm.getClass();
            if (k.value instanceof Boolean) return c.getMethod("getConfigValueBoolNTV", int.class).invoke(cm, k.id);
            if (k.value instanceof Integer) return c.getMethod("getConfigValueIntNTV", int.class).invoke(cm, k.id);
            return c.getMethod("getConfigValueStringNTV", int.class).invoke(cm, k.id);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void write(Object cm, int id, Object value) throws Exception {
        Class<?> c = cm.getClass();
        if (value instanceof Boolean) c.getMethod("setConfigValueBoolNTV", int.class, boolean.class).invoke(cm, id, value);
        else if (value instanceof Integer) c.getMethod("setConfigValueIntNTV", int.class, int.class).invoke(cm, id, value);
        else c.getMethod("setConfigValueStringNTV", int.class, String.class).invoke(cm, id, String.valueOf(value));
    }

    /** [stock] in the kind of [kept]: Waze's defaults come as Boolean, Long or String. */
    private static Object convert(Object stock, Object kept) {
        if (kept instanceof Integer && stock instanceof Number) return ((Number) stock).intValue();
        if (kept instanceof Boolean && stock instanceof Boolean) return stock;
        return kept instanceof String ? String.valueOf(stock) : stock;
    }

    /**
     * Waze's compile-time default for ConfigValues.[name], which is what Waze itself falls back to:
     * each value object keeps a supplier for it in a field whose type is a one-method interface
     * (com.waze.config.e, a()). The other interface-typed field is a Kotlin Lazy, with two methods.
     */
    private static Object wazeDefault(String name) {
        try {
            Object config = Class.forName("com.waze.config.ConfigValues").getField(name).get(null);
            for (Class<?> c = config.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> type = f.getType();
                    if (!type.isInterface()) continue;
                    Method[] methods = type.getDeclaredMethods();
                    if (methods.length != 1 || methods[0].getParameterTypes().length != 0) continue;
                    f.setAccessible(true);
                    Object supplier = f.get(config);
                    if (supplier == null) continue;
                    methods[0].setAccessible(true);
                    return methods[0].invoke(supplier);
                }
            }
        } catch (Throwable t) {
            Log.w("UA", "Waze customization: default of " + name, t);
        }
        return null;
    }

    // ---- features that are code, not configuration --------------------------------------------

    /** AdvilRequest.getPageUrl: no page for Waze's ad server while Hide ads is on. */
    public static String pageUrl(String url) {
        return on("hideAds") ? "" : url;
    }

    // ---- the menu row and the screen ----------------------------------------------------------

    /** The row's icon: Waze's pencil, else Android's. */
    public static int iconRes() {
        Context c = context();
        if (c != null) {
            int id = c.getResources().getIdentifier("ic_outline_edit_24px", "drawable", c.getPackageName());
            if (id != 0) return id;
        }
        return android.R.drawable.ic_menu_edit;
    }

    /**
     * The row's click handler. The patch makes it implement Waze's copy of Kotlin's Function0
     * (h.g.a.a), whose one method is invoke().
     */
    public static final class Open {
        public Object invoke() {
            Activity a = resumed.get();
            if (a != null && !a.isFinishing()) {
                try {
                    show(a);
                } catch (Throwable t) {
                    Log.w("UA", "Waze customization: screen", t);
                }
            }
            return null;
        }
    }

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** A full-screen panel over Waze, in its day or night colours. */
    static void show(Activity a) {
        boolean night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? Color.BLACK : Color.WHITE;
        int text = night ? 0xFFE8EAED : 0xFF202124;
        int sub = night ? 0xFF9AA0A6 : 0xFF5F6368;

        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 8), dp(a, 8), dp(a, 20), dp(a, 8));
        ImageButton back = new ImageButton(a);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setColorFilter(text);
        back.setBackground(null);
        back.setContentDescription("Close");
        back.setOnClickListener(v -> d.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        TextView title = new TextView(a);
        title.setText("Customization");
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(a, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        List<String> list = features();
        if (list.isEmpty()) {
            body.addView(row(a, "Nothing to switch", "No patch with a switch was applied to this Waze.", null, text, sub));
        }
        for (String key : list) {
            String[] label = LABELS.get(key);
            Switch s = new Switch(a);
            s.setChecked(on(key));
            s.setOnCheckedChangeListener((CompoundButton b, boolean isOn) -> set(key, isOn));
            body.addView(row(a, label != null ? label[0] : key, label != null ? label[1] : null, s, text, sub));
        }
        TextView note = new TextView(a);
        note.setText("Switches take effect right away. A screen that still shows the old behaviour picks it up "
                + "after Shut off and reopening Waze. Map skin, Full black and Bigger speedometer are set when "
                + "patching and have no switch.");
        note.setTextColor(sub);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 24));
        body.addView(note);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(bg));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            if (!night) {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
        d.show();
    }

    private static View row(Context c, String title, String summary, Switch s, int text, int sub) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        r.setMinimumHeight(dp(c, 64));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        col.addView(t);
        if (summary != null) {
            TextView st = new TextView(c);
            st.setText(summary);
            st.setTextColor(sub);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(c, 12));
            r.addView(s, lp);
            r.setOnClickListener(v -> s.toggle());
        }
        return r;
    }
}
