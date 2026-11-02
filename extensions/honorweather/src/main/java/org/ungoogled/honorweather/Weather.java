package org.ungoogled.honorweather;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.text.SpannableString;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.hihonor.android.totemweather.entity.ApiInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * What the patch calls in Honor Weather: its weather supplier (Open-Meteo, in place of the list Honor's config server
 * would send), its Retrofit services (HonorServers), the start of each process and the weather screen (Consent).
 */
public final class Weather {
    /** The international supplier the app's parser and screens are built for (AccuWeather's API format). */
    static final int SUPPLIER_ID = 10001;

    private static volatile Context context;

    private Weather() {
    }

    static Context context() {
        Context known = context;
        if (known != null) return known;
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app != null) context = app.getApplicationContext();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return context;
    }

    /** Application.onCreate, in every process: only Open-Meteo may be reached, and only once allowed. */
    public static void start(Context app) {
        if (app != null) context = app.getApplicationContext();
        HonorServers.install();
        HonorServers.quietLateNetworkErrors();
    }

    /** The app's Retrofit service factory: the port's stand-in for each service interface. */
    public static Object service(Class<?> type) {
        return HonorServers.service(type);
    }

    /** The supplier entry: Open-Meteo, answered on the phone (Accu). */
    public static ApiInfo supplier() {
        ApiInfo info = new ApiInfo();
        info.setVenderId(SUPPLIER_ID);
        info.setVendorName("Open-Meteo");
        info.setmServiceUrl(Accu.BASE);
        info.setmApiKey("open-meteo");
        info.setmApiType("small");
        info.setSecretKey("");
        info.setCpId("");
        info.setmApiExprieduration(86400);
        info.setConfigList(new ArrayList<>());
        return info;
    }

    /** The supplier list Honor's config server would send: Open-Meteo only. */
    public static List<ApiInfo> suppliers() {
        List<ApiInfo> list = new ArrayList<>();
        list.add(supplier());
        return list;
    }

    /** The app's name as the launcher shows it. */
    static String label(Context context) {
        return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
    }

    /** One line about the only service the port uses, for the app's first-run notice and the fallback question. */
    static String noticeText(Context context) {
        return label(context) + " gets forecasts from Open-Meteo (open-meteo.com), which receives your approximate location"
                + " or the city you search for.";
    }

    private static String idName(View view) {
        try {
            return view.getId() == View.NO_ID ? "" : view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException e) {
            return "";
        }
    }

    private static void label(Activity activity, String id, String text) {
        int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
        View view = res == 0 ? null : activity.findViewById(res);
        if (view instanceof TextView) ((TextView) view).setText(text);
    }

    /** The app's first-run notice: what it says (in place of Honor's statement) and its two buttons. */
    public static SpannableString notice(Activity activity) {
        if (activity == null) return new SpannableString("");
        label(activity, "hwprivacystatement_positive_button", "Allow");
        label(activity, "hwprivacystatement_positive_button_europe", "Allow");
        label(activity, "hwprivacystatement_negative_button", "Don't allow");
        return new SpannableString(noticeText(activity));
    }

    /** A tap on the notice: Allow or Don't allow is recorded and the app opens (true); anything else is the app's. */
    public static boolean noticeAnswer(Activity activity, View view) {
        if (view == null) return false;
        String id = idName(view);
        if (id.startsWith("hwprivacystatement_positive_button")) {
            Consent.record(true);
            return true;
        }
        if (id.equals("hwprivacystatement_negative_button")) {
            Consent.record(false);
            return true;
        }
        return false;
    }

    /**
     * MagicOS's window action bar is its own toolbar class (com.hihonor.android.widget.HwToolbar, which Weather bundles
     * as a plain Toolbar subclass), and the city list casts the window's action bar to it; Android's is a Toolbar. Before
     * the window is built, the activity's inflater makes that action bar the app's HwToolbar.
     */
    public static void magicActionBar(Activity activity) {
        if (activity == null) return;
        LayoutInflater inflater = activity.getLayoutInflater();
        if (inflater.getFactory() != null || inflater.getFactory2() != null) return;
        final java.lang.reflect.Constructor<?> toolbar;
        try {
            toolbar = Class.forName("com.hihonor.android.widget.HwToolbar", false, activity.getClassLoader())
                    .getConstructor(Context.class, AttributeSet.class);
        } catch (ReflectiveOperationException e) {
            return;
        }
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                if (!"Toolbar".equals(name) && !"android.widget.Toolbar".equals(name)) return null;
                try {
                    return (View) toolbar.newInstance(context, attrs);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                return onCreateView(null, name, context, attrs);
            }
        });
    }

    /** The weather screen came up: asks about Open-Meteo once. */
    public static void home(Activity activity) {
        if (activity != null) activity.getWindow().getDecorView().post(() -> Consent.ask(activity));
    }
}
