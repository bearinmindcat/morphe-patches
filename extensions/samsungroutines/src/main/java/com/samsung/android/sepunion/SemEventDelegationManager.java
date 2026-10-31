package com.samsung.android.sepunion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;

import org.ungoogled.samsungroutines.EventDelegation;

import java.util.List;

/**
 * Stand-in for One UI's event delegation service (names and signatures Modes and Routines uses, no Samsung code): One
 * UI watches broadcasts and content URIs for the app and fires its PendingIntent when one arrives, so the routine
 * conditions work without the app running. Here the app's own always-running engine process does the watching (see
 * EventDelegation).
 */
public class SemEventDelegationManager {
    private final Context context;

    public SemEventDelegationManager() {
        this(null);
    }

    public SemEventDelegationManager(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    public void registerPendingIntent(IntentFilter filter, PendingIntent intent, int flags, List list) {
        if (context != null) EventDelegation.register(context, filter, intent);
    }

    public void unregisterPendingIntent(IntentFilter filter, PendingIntent intent) {
        if (context != null) EventDelegation.unregister(context, filter, intent);
    }

    public void registerContentUri(Uri uri, PendingIntent intent) {
        if (context != null) EventDelegation.register(context, uri, intent);
    }

    public void unregisterContentUri(Uri uri, PendingIntent intent) {
        if (context != null) EventDelegation.unregister(context, uri, intent);
    }

    /** One UI's own events ("monitor_call_state"): not available off One UI. */
    public void registerCustomEvent(String event, PendingIntent intent, Bundle extras) {
    }

    public void unregisterCustomEvent(String event, PendingIntent intent, Bundle extras) {
    }

    public void unregisterAll() {
        if (context != null) EventDelegation.unregisterAll(context);
    }
}
