package org.ungoogled.samsungphone;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Blocking a number. Android keeps one blocked-number list that only the default phone app (and the system) may change,
 * and this app is never the phone app: blocking or unblocking opens Android's blocked numbers screen, where the user
 * adds or removes the number, with a short note saying so.
 */
final class Blocking {
    private Blocking() {
    }

    static void manage(String number, boolean block) {
        Context context = Calls.app();
        if (context == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                TelecomManager telecom = context.getSystemService(TelecomManager.class);
                Intent intent = telecom != null ? telecom.createManageBlockedNumbersIntent() : null;
                if (intent == null) intent = new Intent("android.telecom.action.MANAGE_BLOCKED_NUMBERS");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                String what = number == null ? "this number" : number;
                String text = (block ? "Block " : "Unblock ") + what + " here: your phone app keeps the blocked numbers.";
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w("UA", "no blocked numbers screen: " + e);
                Toast.makeText(context, "Block numbers in your phone app.", Toast.LENGTH_LONG).show();
            }
        });
    }
}
