package org.ungoogled.coloroscontacts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Where OnePlus Contacts places a call. On ColorOS it is the phone app and starts calls with CALL_PRIVILEGED; here it
 * is never the phone app (the patch removes its in-call service, so Android does not even offer it as one), and the
 * user's own phone app shows every call. So every call goes to Android's Telecom as an ordinary app's ACTION_CALL
 * (Telecom routes an emergency number from an ordinary app to the phone app itself). An emergency number, a call
 * Android refuses (no Phone permission) and anything that fails open the user's phone app with the number dialled in
 * instead, so pressing call never ends in nothing.
 */
public final class Calls {
    private static final String TAG = "UA";
    private static final String CALL = Intent.ACTION_CALL;
    private static final String CALL_PRIVILEGED = "android.intent.action.CALL_PRIVILEGED";
    private static final String CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY";
    private static final String EMERGENCY = "emergency number";

    private static volatile Context app;

    private Calls() {
    }

    /** From Application.onCreate: TelecomManager.placeCall carries no Context of its own. */
    public static void init(Context context) {
        if (context == null) return;
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        CallHistoryNote.init(context);
    }

    static Context app() {
        return app;
    }

    private static boolean isCall(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        return CALL.equals(action) || CALL_PRIVILEGED.equals(action) || CALL_EMERGENCY.equals(action);
    }

    // Context / Activity starts. Anything that is not a call is started as before; one aimed at a ColorOS app this
    // phone lacks (ColorOS's call settings, messages, ...) goes to whichever app handles it.

    public static void startActivity(Context context, Intent intent) {
        if (isCall(intent)) {
            call(context, intent);
            return;
        }
        intent = own(context, intent);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent open = elsewhere(context, intent, e);
            if (!(context instanceof Activity)) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
        }
    }

    public static void startActivity(Context context, Intent intent, Bundle options) {
        if (isCall(intent)) {
            call(context, intent);
            return;
        }
        intent = own(context, intent);
        try {
            context.startActivity(intent, options);
        } catch (ActivityNotFoundException e) {
            Intent open = elsewhere(context, intent, e);
            if (!(context instanceof Activity)) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open, options);
        }
    }

    public static void startActivityForResult(Activity activity, Intent intent, int request) {
        if (isCall(intent)) {
            call(activity, intent);
            return;
        }
        intent = own(activity, intent);
        try {
            activity.startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            activity.startActivityForResult(elsewhere(activity, intent, e), request);
        }
    }

    public static void startActivityForResult(Activity activity, Intent intent, int request, Bundle options) {
        if (isCall(intent)) {
            call(activity, intent);
            return;
        }
        intent = own(activity, intent);
        try {
            activity.startActivityForResult(intent, request, options);
        } catch (ActivityNotFoundException e) {
            activity.startActivityForResult(elsewhere(activity, intent, e), request, options);
        }
    }

    /** ColorOS's own actions (and the app's), which its screens use to open each other. */
    private static final String[] OWN_ACTIONS = {
            "com.oplus.", "com.oppo.", "com.coloros.", "oplus.", "oppo.", "com.android.contacts.",
    };

    /**
     * An implicit intent with one of ColorOS's own actions that this app answers stays in this app. On an OPPO,
     * OnePlus or realme phone the phone's own Contacts app (com.android.contacts) answers the same actions, and the
     * app's own screens (editing a contact, ...) opened a chooser between the two copies.
     */
    static Intent own(Context context, Intent intent) {
        if (intent == null || intent.getComponent() != null || intent.getPackage() != null) return intent;
        String action = intent.getAction();
        if (action == null) return intent;
        boolean colorOs = false;
        for (String prefix : OWN_ACTIONS) colorOs |= action.startsWith(prefix);
        if (!colorOs) return intent;
        Intent mine = new Intent(intent).setPackage(context.getPackageName());
        try {
            if (context.getPackageManager().resolveActivity(mine, 0) == null) return intent;
        } catch (RuntimeException e) {
            return intent;
        }
        return mine;
    }

    /**
     * The same intent without its ColorOS target, for whichever app handles it: an intent aimed at an app this phone
     * lacks (ColorOS's Messages com.android.mms, its call settings and SIM contacts in com.android.phone, OPPO's own
     * apps) loses that aim; ColorOS's call settings become the phone app's. Rethrows when no other app handles it.
     */
    private static Intent elsewhere(Context context, Intent intent, ActivityNotFoundException e) {
        String target = intent.getPackage() != null ? intent.getPackage()
                : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
        if ("com.android.phone".equals(target) && intent.getComponent() != null
                && intent.getComponent().getClassName().contains("CallFeaturesSetting")) {
            Log.i(TAG, "start: no ColorOS call settings, the phone app's call settings open");
            return new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        String action = intent.getAction();
        if (target == null || action == null || Intent.ACTION_MAIN.equals(action) || target.equals(context.getPackageName())) {
            throw e;
        }
        Intent open = new Intent(intent);
        open.setPackage(null);
        open.setComponent(null);
        if (context.getPackageManager().resolveActivity(open, 0) == null) throw e;
        Log.i(TAG, "start: " + target + " cannot open " + action + ", any app may");
        return open;
    }

    // TelecomManager.placeCall

    public static void placeCall(TelecomManager telecom, Uri uri, Bundle extras) {
        Context context = app;
        try {
            if (context == null) {
                telecom.placeCall(uri, extras);
                return;
            }
            String why = refusal(context, uri);
            if (why != null) {
                handOver(context, uri, why);
                return;
            }
            telecom.placeCall(uri, extras);
        } catch (RuntimeException e) {
            if (context != null) handOver(context, uri, "placeCall failed: " + e);
            else Log.w(TAG, "call: placeCall failed and no context to hand it over: " + e);
        }
    }

    /** Why this call goes to the user's phone app instead of Telecom, or null to place it. */
    private static String refusal(Context context, Uri uri) {
        if (uri == null) return "no number";
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "no Phone permission";
        }
        if (!"tel".equals(uri.getScheme())) return null;
        String number = uri.getSchemeSpecificPart();
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm == null) return "no telephony";
            if (tm.isEmergencyNumber(number)) return EMERGENCY;
        } catch (RuntimeException e) {
            // Cannot tell: the user's phone app decides.
            return "emergency check failed: " + e;
        }
        return null;
    }

    private static void call(Context context, Intent intent) {
        Uri uri = intent.getData();
        try {
            String why = refusal(context, uri);
            if (why != null) {
                handOver(context, uri, why);
                return;
            }
            Intent call = new Intent(intent);
            call.setAction(CALL);
            call.setComponent(null);
            call.setPackage(null);
            if (!(context instanceof Activity)) call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "call: to Telecom (ACTION_CALL)");
            context.startActivity(call);
        } catch (RuntimeException e) {
            handOver(context, uri, "ACTION_CALL failed: " + e);
        }
    }

    /**
     * Opens the user's phone app (the default one, else the system one, else any other dialer) with the number
     * dialled in, as Android does for an ordinary app's emergency call: the user presses call there.
     */
    static void handOver(Context context, Uri uri, String why) {
        Log.i(TAG, "call: handed to the phone app (" + why + ")");
        Intent dial = new Intent(Intent.ACTION_DIAL);
        if (uri != null && "tel".equals(uri.getScheme())) dial.setData(uri);
        dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String self = context.getPackageName();
        String[] candidates = new String[2];
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null) {
                candidates[0] = telecom.getDefaultDialerPackage();
                candidates[1] = telecom.getSystemDialerPackage();
            }
        } catch (RuntimeException e) {
            // Fall through to any other dialer.
        }
        for (String pkg : candidates) {
            if (pkg == null || pkg.equals(self)) continue;
            try {
                context.startActivity(new Intent(dial).setPackage(pkg));
                if (EMERGENCY.equals(why)) toast(context, "Emergency number: your phone app places this call.");
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "call: " + pkg + " cannot dial: " + e);
            }
        }
        try {
            List<ResolveInfo> dialers = context.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), 0);
            for (ResolveInfo info : dialers) {
                if (info.activityInfo == null || self.equals(info.activityInfo.packageName)) continue;
                ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                context.startActivity(new Intent(dial).setComponent(component));
                return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "call: no dialer took the number: " + e);
        }
        try {
            // Last resort: Android's own emergency dialer (the lock screen's), which any app may open.
            Intent emergency = new Intent("com.android.phone.EmergencyDialer.DIAL");
            if (dial.getData() != null) emergency.setData(dial.getData());
            context.startActivity(emergency.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "call: no emergency dialer: " + e);
        }
        toast(context, "No phone app to place this call.");
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context.getApplicationContext() != null ? context.getApplicationContext() : context, text,
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            // Off the main thread without a looper: the phone app opening is the message.
        }
    }
}
