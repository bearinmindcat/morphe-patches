package org.ungoogled.ui;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaSession;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Media playing elsewhere: Android lists its active media sessions only to an app holding notification access,
 * which needs a listener like this one. Normally it lets the system unbind it straight away (the access stays
 * granted). While navigation's media panel is up it stays bound and keeps the sessions of the apps' media
 * notifications: an app may play through a session it never makes active (Jellyfin's web player, issue #78),
 * which the active sessions never list but its notification still names.
 */
public final class MediaListener extends NotificationListenerService {
    private static volatile MediaListener live;
    private static volatile boolean wanted;
    /** Notification key -> the media session it shows, with its package. */
    private static final Map<String, Object[]> SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void onListenerConnected() {
        try {
            if (!wanted) {
                requestUnbind();
                return;
            }
            live = this;
            SESSIONS.clear();
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification n : all) posted(n);
            PlayingMedia.notificationsChanged();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
        SESSIONS.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification n) {
        if (posted(n)) PlayingMedia.notificationsChanged();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification n) {
        if (n != null && SESSIONS.remove(n.getKey()) != null) PlayingMedia.notificationsChanged();
    }

    private static boolean posted(StatusBarNotification n) {
        if (n == null || n.getNotification() == null) return false;
        Object token = n.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (!(token instanceof MediaSession.Token)) return SESSIONS.remove(n.getKey()) != null;
        Object[] old = SESSIONS.put(n.getKey(), new Object[] {n.getPackageName(), token});
        return old == null || !old[1].equals(token);
    }

    /** Stays bound (the media panel is up) or not. */
    static void want(Context c, boolean on) {
        wanted = on;
        try {
            MediaListener l = live;
            if (on && l == null) requestRebind(new ComponentName(c, MediaListener.class));
            else if (!on && l != null) l.requestUnbind();
        } catch (Throwable ignored) {
        }
    }

    /** The sessions named by the media notifications of [pkg] (any package for null). */
    static List<MediaSession.Token> notified(String pkg) {
        List<MediaSession.Token> out = new ArrayList<>();
        for (Object[] s : SESSIONS.values()) if (pkg == null || pkg.equals(s[0])) out.add((MediaSession.Token) s[1]);
        return out;
    }
}
