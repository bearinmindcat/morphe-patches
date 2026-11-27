package org.ungoogled.honorclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.icu.util.ULocale;
import android.util.Log;

import java.util.Locale;

/**
 * The alarm editor's "Working days (skip holidays)" switch downloads China's statutory holidays from Honor's server
 * (configserver-drcn.platform.hihonorcloud.com, with the phone model in a header). Clock uses that data only on a
 * Chinese ROM with a Simplified-Chinese UI (its own test, com.android.util.y.B0); everywhere else such an alarm rings
 * Monday to Friday. Stock Clock downloaded it on every ROM the moment the switch was turned on, with no word about
 * Honor. Now it downloads only where the data is used, and only after the user allowed Honor's server here.
 */
public final class Holidays {
    private static final String TAG = "Holidays";
    private static final String PREFS = "org.ungoogled.honor_consent";
    private static final String ALLOWED = "holidays_allowed";

    private static boolean asking;

    private Holidays() {
    }

    /** Clock's own test: ro.product.locale language_region "zh_CN" and a Simplified-Chinese UI. */
    static boolean chineseRom() {
        try {
            String region = Magic.prop("ro.product.locale.language", "") + "_" + Magic.prop("ro.product.locale.region", "");
            Locale locale = Locale.getDefault();
            boolean hans = "Hans".equals(ULocale.addLikelySubtags(ULocale.forLanguageTag(locale.toLanguageTag())).getScript())
                    && "zh".equals(locale.getLanguage());
            return "zh_CN".equals(region) && hans;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Gate of DownloadChineseRecessHelper's two downloads (the switch's and the weekly update). */
    public static boolean allowed(Context context) {
        try {
            boolean allowed = chineseRom() && context != null
                    && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOWED, false);
            if (!allowed) Log.i(TAG, "holiday download skipped: " + (chineseRom() ? "Honor's server not allowed" : "not a Chinese ROM"));
            return allowed;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The switch's downloadHolidayDataDirection(Context, boolean) call: asks first on a Chinese ROM. */
    public static void download(Context context, boolean delay) {
        if (!chineseRom()) {
            Log.i(TAG, "not a Chinese ROM: holiday data is not used, not downloaded");
            return;
        }
        if (allowed(context)) {
            start(context, delay);
            return;
        }
        if (!(context instanceof Activity) || asking || ((Activity) context).isFinishing()) return;
        Activity activity = (Activity) context;
        try {
            boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            asking = true;
            // Only shown with a Simplified-Chinese UI. "Holiday dates for working-day alarms come from Honor's server
            // (...), which receives the phone model. Allow the download?" -- Allow / Don't allow.
            AlertDialog dialog = new AlertDialog.Builder(activity, night
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setMessage("工作日闹钟所需的法定节假日数据来自荣耀的服务器（configserver-drcn.platform.hihonorcloud.com），"
                            + "请求中会包含手机型号。是否允许下载？")
                    .setCancelable(false)
                    .setPositiveButton("允许", (d, which) -> {
                        asking = false;
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ALLOWED, true).apply();
                        start(activity, delay);
                    })
                    .setNegativeButton("不允许", (d, which) -> asking = false)
                    .show();
            int accent = night ? 0xFF8AB4F8 : 0xFF1A73E8;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
        } catch (RuntimeException e) {
            asking = false;
        }
    }

    private static void start(Context context, boolean delay) {
        try {
            Class.forName("com.android.util.DayOfWeekRepeatUtil")
                    .getMethod("downloadHolidayDataDirection", Context.class, boolean.class)
                    .invoke(null, context, delay);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "holiday download: " + e);
        }
    }
}
