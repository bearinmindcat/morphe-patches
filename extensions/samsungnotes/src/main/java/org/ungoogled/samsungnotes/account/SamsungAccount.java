package org.ungoogled.samsungnotes.account;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

/**
 * Samsung account sign-in without the Samsung Account app (experimental).
 *
 * <p>Notes gets its Samsung account from the Samsung Account system app: the account name from Android's account list,
 * the access token from the app's bound service, sign-in from the app's screens. Phones without that app now get the
 * same answers from an account signed in on Samsung's own sign-in page (account.samsung.com) with Notes' own client ID,
 * so Notes runs its normal Samsung account mode and its own sync engine. Nothing about the phone is changed: Samsung's
 * servers see this phone as what it is.
 *
 * <p>The patch calls the static methods here from Notes' account code (see the patch for where).
 */
public final class SamsungAccount {
    static final String TAG = SamsungAuth.TAG;
    public static final String ACCOUNT_APP = "com.osp.app.signin";
    static final String SIGNED_IN = "com.samsung.account.SAMSUNGACCOUNT_SIGNIN_COMPLETED";
    static final String SIGNED_OUT = "com.samsung.account.SAMSUNGACCOUNT_SIGNOUT_COMPLETED";
    /** Notes' Samsung account service ID (CommonUtils.getAppServiceId), if the app's own lookup fails. */
    private static final String NOTES_CLIENT_ID = "n9s985jz7n";
    private static final String CHANNEL = "ungoogled_samsung_account";

    private static volatile Context app;
    private static volatile Boolean accountApp;

    private SamsungAccount() {
    }

    private static void remember(Context context) {
        if (app == null && context != null) app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private static Context app() {
        Context c = app;
        if (c != null) return c;
        try {
            c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            c = null;
        }
        if (c != null) app = c;
        return c;
    }

    /** Whether the phone has the Samsung Account app. */
    static boolean accountAppInstalled(Context context) {
        Boolean known = accountApp;
        if (known != null) return known;
        boolean installed;
        try {
            context.getPackageManager().getPackageInfo(ACCOUNT_APP, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        } catch (RuntimeException e) {
            installed = false;
        }
        accountApp = installed;
        return installed;
    }

    /** True when this sign-in answers for the missing Samsung Account app. */
    public static boolean web(Context context) {
        remember(context);
        Context c = context != null ? context : app();
        return c != null && !accountAppInstalled(c);
    }

    // ---- called from Notes

    /**
     * CommonUtils.hasSamsungAccountPackage: yes. Without the app this sign-in stands in for it, so Notes uses its normal
     * Samsung account mode instead of its unfinished web mode ("Not supported yet!").
     */
    public static boolean hasAccountApp(Context context) {
        remember(context);
        return true;
    }

    /** The signed-in account's name (Notes reads it from Android's account list), null when signed out. */
    public static String accountName(Context context) {
        remember(context);
        Context c = context != null ? context : app();
        if (c == null) return null;
        AccountStore store = AccountStore.get(c);
        return store.signedIn() ? store.loginName() : null;
    }

    /** Context.bindService for Notes' Samsung Account service: without the app, connected to the local one at once. */
    public static boolean bindService(Context context, Intent intent, ServiceConnection connection, int flags) {
        ComponentName target = intent.getComponent();
        boolean account = (target != null && ACCOUNT_APP.equals(target.getPackageName())) || ACCOUNT_APP.equals(intent.getPackage());
        if (!account || !web(context)) return context.bindService(intent, connection, flags);
        IBinder binder = AccountService.binder(context);
        if (binder == null) return false;
        connection.onServiceConnected(new ComponentName(ACCOUNT_APP, "com.msc.sa.service.RequestService"), binder);
        return true;
    }

    /** Context.unbindService for the same connection: nothing was bound without the app. */
    public static void unbindService(Context context, ServiceConnection connection) {
        if (web(context)) return;
        context.unbindService(connection);
    }

    /** The package Notes' Samsung account screens go to: the Samsung Account app, or Notes' own sign-in screen. */
    public static String accountAppPackage() {
        Context c = app();
        if (c == null || !web(c)) return ACCOUNT_APP;
        return c.getPackageName();
    }

    /**
     * Notes' "may this phone sync with a Samsung account?" (DeviceUtils.isSupportedAccount, which also wants a Samsung
     * phone): yes without the app, as the web sign-in provides the account. Galaxy AI and note locks keep their check.
     */
    public static boolean syncSupported(Object deviceUtils, Context context) {
        if (context == null) return false;
        if (web(context)) return true;
        try {
            return (Boolean) deviceUtils.getClass().getMethod("isSupportedAccount", Context.class).invoke(deviceUtils, context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ---- shared

    static String clientId() {
        try {
            Object id = Class.forName("com.samsung.android.support.senl.nt.base.common.util.CommonUtils")
                .getMethod("getAppServiceId").invoke(null);
            if (id instanceof String && !((String) id).isEmpty()) return (String) id;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to the ID this Notes build uses.
        }
        return NOTES_CLIENT_ID;
    }

    /** Tells Notes' own account receiver, as the Samsung Account app does after signing in or out. */
    static void announce(Context context, boolean signedIn) {
        Intent intent = new Intent(signedIn ? SIGNED_IN : SIGNED_OUT);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** Sign-out: forgets the tokens and the sign-in page's cookies, then tells Notes. Notes' notes stay. */
    static void signOut(Context context) {
        AccountStore.get(context).clear();
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (RuntimeException e) {
            Log.w(TAG, "web session not cleared: " + e);
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(CHANNEL, 1);
        announce(context, false);
    }

    /** The refresh token was refused: a notification that opens the sign-in page again. */
    static void askToSignInAgain(Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Samsung account", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(context, SignInActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Samsung account")
                .setContentText("Sign in again to keep syncing your notes.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
            nm.notify(CHANNEL, 1, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "sign-in reminder not shown: " + e);
        }
    }
}
