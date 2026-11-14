package org.ungoogled.gmail;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcelable;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Mark as read" on the new-mail notifications of Exchange, IMAP and POP3 accounts (Google accounts have it already).
 * The button hands the conversation to Gmail's own mark-read-and-resend action of its mail service, which marks it
 * read and seen in Gmail's provider (the account's sync carries that to the server) and rebuilds the notifications.
 */
public final class MarkRead {
    private static final String TAG = "UA";
    /** Gmail's mail service and the action it marks a conversation read with (declared in its own intent filter). */
    private static final String SERVICE = "com.google.android.apps.gmail.libraries.intent.GmailIntentService";
    private static final String ACTION = "com.android.mail.action.MARK_READ_RESEND_NOTIFICATIONS";
    private static final int KEEP = 64;

    /** Notification id to {account, folder, conversation uri}, filled while Gmail builds the notification's actions. */
    private static final Map<Integer, Object[]> CONVERSATIONS = new LinkedHashMap<Integer, Object[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Object[]> eldest) {
            return size() > KEEP;
        }
    };

    private MarkRead() {}

    /** From the constructor of a notification's Archive/Delete/Reply action: which conversation notification [id] shows. */
    public static void remember(int id, Parcelable account, Parcelable folder, Uri conversation) {
        if (account == null || folder == null || conversation == null) return;
        synchronized (CONVERSATIONS) {
            CONVERSATIONS.put(id, new Object[] {account, folder, conversation});
        }
    }

    /** Just before Gmail posts notification [id]: a conversation's notification gets the button after its first action. */
    public static void add(Context context, int id, Notification notification, int icon, int label) {
        try {
            Object[] conversation;
            synchronized (CONVERSATIONS) {
                conversation = CONVERSATIONS.get(id);
            }
            if (conversation == null || notification == null) return;
            Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(context, SERVICE))
                .setData((Uri) conversation[2])
                .putExtra("mail_account", (Parcelable) conversation[0])
                .putExtra("folder", (Parcelable) conversation[1])
                .putExtra("conversationUri", (Uri) conversation[2]);
            PendingIntent pending = PendingIntent.getService(context, id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(context, icon), context.getString(label), pending)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .build();
            Notification.Action[] old = notification.actions == null ? new Notification.Action[0] : notification.actions;
            Notification.Action[] actions = new Notification.Action[old.length + 1];
            int at = Math.min(1, old.length);
            System.arraycopy(old, 0, actions, 0, at);
            actions[at] = action;
            System.arraycopy(old, at, actions, at + 1, old.length - at);
            notification.actions = actions;
        } catch (Throwable t) {
            Log.w(TAG, "mark as read button", t);
        }
    }
}
