package org.ungoogled.samsungphone;

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
 * Where Samsung Phone places a call. On One UI it is the phone app and starts calls with CALL_PRIVILEGED; here it is
 * never the phone app (the patch removes its in-call service, so Android does not even offer it as one), and the
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

    // Context / Activity starts. Anything that is not a call is started as before; one aimed at a Samsung app this
    // phone lacks (Samsung Contacts for a contact's details, ...) goes to whichever app handles it.

    public static void startActivity(Context context, Intent intent) {
        if (isCall(intent)) {
            call(context, intent);
            return;
        }
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent open = elsewhere(intent, e);
            if (!(context instanceof Activity)) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
        }
    }

    public static void startActivity(Context context, Intent intent, Bundle options) {
        if (isCall(intent)) {
            call(context, intent);
            return;
        }
        try {
            context.startActivity(intent, options);
        } catch (ActivityNotFoundException e) {
            Intent open = elsewhere(intent, e);
            if (!(context instanceof Activity)) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open, options);
        }
    }

    public static void startActivityForResult(Activity activity, Intent intent, int request) {
        if (isCall(intent)) {
            call(activity, intent);
            return;
        }
        try {
            activity.startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            activity.startActivityForResult(elsewhere(intent, e), request);
        }
    }

    public static void startActivityForResult(Activity activity, Intent intent, int request, Bundle options) {
        if (isCall(intent)) {
            call(activity, intent);
            return;
        }
        try {
            activity.startActivityForResult(intent, request, options);
        } catch (ActivityNotFoundException e) {
            activity.startActivityForResult(elsewhere(intent, e), request, options);
        }
    }

    /**
     * The same intent without its Samsung target, for whichever app handles it: Samsung Contacts' own actions for a
     * contact become Android's (view, edit), an intent aimed at an absent Samsung app loses that aim. Rethrows when
     * neither applies.
     */
    private static Intent elsewhere(Intent intent, ActivityNotFoundException e) {
        String standard = null;
        if ("com.samsung.contacts.action.VIEW_CONTACT".equals(intent.getAction())) standard = Intent.ACTION_VIEW;
        else if ("com.samsung.contacts.action.EDIT_CONTACT".equals(intent.getAction())) standard = Intent.ACTION_EDIT;
        if (standard != null && intent.getData() != null) {
            Log.i(TAG, "start: no Samsung Contacts, any contacts app may open " + standard);
            Intent open = new Intent(intent);
            open.setAction(standard);
            open.setPackage(null);
            open.setComponent(null);
            return open;
        }
        if ("com.samsung.android.app.telephonyui.action.OPEN_CALL_SETTINGS".equals(intent.getAction())) {
            // One UI's call settings are a separate system app: the user's phone app's call settings stand in.
            Log.i(TAG, "start: no Samsung call settings, the phone app's call settings open");
            return new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        String target = intent.getPackage() != null ? intent.getPackage()
                : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
        boolean samsung = target != null && (target.startsWith("com.samsung.") || target.startsWith("com.sec."));
        if (!samsung || intent.getAction() == null) {
            throw e;
        }
        Log.i(TAG, "start: " + target + " is not installed, any app may open " + intent.getAction());
        Intent open = new Intent(intent);
        open.setPackage(null);
        open.setComponent(null);
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
