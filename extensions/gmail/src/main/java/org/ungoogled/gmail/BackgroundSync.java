package org.ungoogled.gmail;

import android.accounts.Account;
import android.app.Application;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

/**
 * microG Gmail, Google accounts: Gmail syncs them when it is opened and when a push (Chime, over microG-RE) says
 * there is new mail. Without the push -- microG-RE before 7.4.0 never routed Gmail's, and Google's side may still
 * refuse a renamed app -- mail only came in when Gmail was opened (issue #216). Gmail's Android sync is now on for
 * microG-RE's Google accounts (it was never switched on), and runs every few minutes as well, for every Google
 * account the user syncs Gmail for (the same sync "Sync now" in the account's settings starts).
 */
public final class BackgroundSync {
    private static final String TAG = "UA";
    /** microG-RE's account type and accounts provider (Gmail lists Google accounts through it). */
    private static final String ACCOUNT_TYPE = "app.revanced";
    private static final String ACCOUNTS_PROVIDER = "app.revanced.android.gms.auth.accounts";
    /** Gmail's sync authority for Google accounts, under microG Gmail's package (the patch moves "gmail-ls" there). */
    private static final String AUTHORITY_SUFFIX = ".gmail-ls";

    private BackgroundSync() {}

    /** From the app component factory, as Gmail's Application is made: [minutes] 0 removes the periodic sync. */
    public static void start(Application app, int minutes) {
        if (app == null) return;
        new Thread(() -> schedule(app, minutes), "UA-gmail-sync").start();
    }

    private static void schedule(Application app, int minutes) {
        try {
            // Started as the Application is made, before Android attaches its context: wait for that, or nothing is set up.
            for (int i = 0; app.getBaseContext() == null && i < 600; i++) Thread.sleep(50);
            Context context = app.getBaseContext();
            if (context == null) return;
            String authority = context.getPackageName() + AUTHORITY_SUFFIX;
            for (Account account : googleAccounts(context)) {
                // Play services turns Gmail's sync on when it adds a Google account; microG-RE does not, so it stayed
                // off (never set up) and Android never synced Gmail by itself. Once, as Play services would: a
                // user who turns it off later keeps it off.
                if (ContentResolver.getIsSyncable(account, authority) < 0) {
                    ContentResolver.setIsSyncable(account, authority, 1);
                    ContentResolver.setSyncAutomatically(account, authority, true);
                }
                if (minutes > 0 && ContentResolver.getIsSyncable(account, authority) > 0
                        && ContentResolver.getSyncAutomatically(account, authority)) {
                    ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, minutes * 60L);
                } else {
                    ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "gmail background sync", t);
        }
    }

    /** The Google accounts microG-RE holds, asked the way Gmail asks (its accounts provider). */
    private static Account[] googleAccounts(Context context) throws Exception {
        ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(ACCOUNTS_PROVIDER);
        if (client == null) return new Account[0];
        try {
            Bundle extras = new Bundle();
            extras.putString("callingActivity", "");
            Bundle result = client.call("get_accounts", ACCOUNT_TYPE, extras);
            Parcelable[] found = result == null ? null : result.getParcelableArray("accounts");
            if (found == null) return new Account[0];
            Account[] accounts = new Account[found.length];
            for (int i = 0; i < found.length; i++) accounts[i] = (Account) found[i];
            return accounts;
        } finally {
            client.close();
        }
    }
}
