package org.ungoogled.coloroscontacts;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * OnePlus Contacts' TelecomManager calls. On ColorOS it is the phone app; here it is an ordinary app, so the calls that
 * need the phone app's role or the Phone permission (missed call notification, in-call screen, phone accounts) are
 * refused with SecurityException, which crashed the app. A refused call answers "nothing"; placing a call goes
 * through Calls.
 */
public final class Telecom {
    private static final String TAG = "UA";

    private Telecom() {
    }

    private static void refused(String call, RuntimeException e) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "telecom: " + call + " refused: " + e.getMessage());
    }

    public static void placeCall(TelecomManager o, Uri uri, Bundle extras) {
        Calls.placeCall(o, uri, extras);
    }

    public static void cancelMissedCallsNotification(TelecomManager o) {
        try {
            o.cancelMissedCallsNotification();
        } catch (SecurityException e) {
            refused("cancelMissedCallsNotification", e);
        }
    }

    public static boolean isInCall(TelecomManager o) {
        try {
            return o.isInCall();
        } catch (SecurityException e) {
            refused("isInCall", e);
            return false;
        }
    }

    public static void showInCallScreen(TelecomManager o, boolean dialpad) {
        try {
            o.showInCallScreen(dialpad);
        } catch (SecurityException e) {
            refused("showInCallScreen", e);
        }
    }

    public static List getCallCapablePhoneAccounts(TelecomManager o) {
        try {
            return o.getCallCapablePhoneAccounts();
        } catch (SecurityException e) {
            refused("getCallCapablePhoneAccounts", e);
            return Collections.emptyList();
        }
    }

    public static List getSelfManagedPhoneAccounts(TelecomManager o) {
        try {
            return o.getSelfManagedPhoneAccounts();
        } catch (SecurityException e) {
            refused("getSelfManagedPhoneAccounts", e);
            return Collections.emptyList();
        }
    }

    public static PhoneAccountHandle getUserSelectedOutgoingPhoneAccount(TelecomManager o) {
        try {
            return o.getUserSelectedOutgoingPhoneAccount();
        } catch (SecurityException e) {
            refused("getUserSelectedOutgoingPhoneAccount", e);
            return null;
        }
    }

    public static PhoneAccount getPhoneAccount(TelecomManager o, PhoneAccountHandle handle) {
        try {
            return o.getPhoneAccount(handle);
        } catch (SecurityException e) {
            refused("getPhoneAccount", e);
            return null;
        }
    }
}
