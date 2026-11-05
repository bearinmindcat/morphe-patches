package com.hihonor.android.content;

/**
 * Stand-in (written by hand): MagicOS's per-user Context calls, answered for the app's own user (the only one an
 * installed app may use).
 */
public class ContextEx {
    public static android.content.Context createPackageContextAsUser(android.content.Context p0, java.lang.String p1, int p2, android.os.UserHandle p3) {
        try { return p0.createPackageContext(p1, p2); } catch (Exception e) { return null; }
    }
    public static int getUserId(android.content.Context p0) {
        return android.os.Process.myUserHandle().hashCode();
    }
    public static android.content.Intent registerReceiverAsUser(android.content.Context p0, android.content.BroadcastReceiver p1, android.os.UserHandle p2, android.content.IntentFilter p3, java.lang.String p4, android.os.Handler p5, int p6) {
        int flags = p6;
        // Android 14 wants every receiver of an installed app marked; the system's broadcasts reach either kind.
        if ((flags & (android.content.Context.RECEIVER_EXPORTED | android.content.Context.RECEIVER_NOT_EXPORTED)) == 0) {
            flags |= android.content.Context.RECEIVER_EXPORTED;
        }
        try {
            return p0.registerReceiver(p1, p3, p4, p5, flags);
        } catch (RuntimeException e) {
            return null;
        }
    }
    public static void startActivityAsUser(android.content.Context p0, android.content.Intent p1, android.os.Bundle p2, android.os.UserHandle p3) {
        if (!(p0 instanceof android.app.Activity)) p1.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        p0.startActivity(p1, p2);
    }
}
