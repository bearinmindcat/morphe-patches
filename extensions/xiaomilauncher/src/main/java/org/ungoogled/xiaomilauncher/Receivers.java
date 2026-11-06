package org.ungoogled.xiaomilauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

/**
 * Receivers the launcher registers at run time without saying whether other apps may send to them (a system app may;
 * an installed app targeting Android 14+ gets a SecurityException): exported, as Android registered them before Android
 * 14 (patches/.../coloros/ColorOsReceivers.kt sends the calls here). Flags that say either are kept.
 */
public final class Receivers {
    private Receivers() {
    }

    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    }

    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler handler) {
        return context.registerReceiver(receiver, filter, permission, handler, Context.RECEIVER_EXPORTED);
    }

    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return context.registerReceiver(receiver, filter, exported(flags));
    }

    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler handler, int flags) {
        return context.registerReceiver(receiver, filter, permission, handler, exported(flags));
    }

    private static int exported(int flags) {
        int either = Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED;
        return (flags & either) == 0 ? flags | Context.RECEIVER_EXPORTED : flags;
    }
}
