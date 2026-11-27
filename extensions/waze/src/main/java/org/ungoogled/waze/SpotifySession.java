package org.ungoogled.waze;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotify in Waze's audio player when Waze is not signed by Waze (issue #213). Spotify's media browser lets in only
 * the apps it knows by signature, so a re-signed Waze's player stayed at "Nothing is playing". Spotify's media
 * session is open to an app with notification access, though: this media browser of Waze's own (connected to only by
 * Waze) hands Waze's player a session of Waze's that follows Spotify's. Track, artwork and state are Spotify's; play,
 * pause, skip, seek and Spotify's own buttons go to Spotify. Nothing is open to browse: the list is empty. The session
 * is never active, so it gets no media buttons and shows nowhere else.
 */
public final class SpotifySession extends MediaBrowserService {
    private static final String TAG = "UA";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final MediaMetadata NOTHING = new MediaMetadata.Builder().build();
    private static final PlaybackState STOPPED = new PlaybackState.Builder()
            .setState(PlaybackState.STATE_NONE, 0, 0f).build();

    /** Waze's session handed to Waze's player, while it is connected. */
    private static MediaSession copy;
    /** Spotify's session it follows, or null. */
    private static MediaController spotify;
    private static MediaController.Callback following;
    private static MediaSessionManager.OnActiveSessionsChangedListener watching;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            setSessionToken(start(this));
        } catch (Throwable t) {
            Log.w(TAG, "Waze Spotify session", t);
        }
    }

    @Override
    public void onDestroy() {
        stop(this);
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return clientUid == Process.myUid() ? new BrowserRoot("spotify", null) : null;
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<>());
    }

    private static synchronized MediaSession.Token start(Context c) {
        Context app = c.getApplicationContext();
        if (copy == null) {
            copy = new MediaSession(app, "UA Spotify");
            copy.setCallback(new Forward(), main);
            copy.setMetadata(NOTHING);
            copy.setPlaybackState(STOPPED);
        }
        try {
            MediaSessionManager m = app.getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(app, MediaAccess.class);
            if (watching == null) {
                watching = sessions -> follow(spotifyOf(sessions));
                m.addOnActiveSessionsChangedListener(watching, listener, main);
            }
            follow(spotifyOf(m.getActiveSessions(listener)));
        } catch (SecurityException e) {
            Log.i(TAG, "Waze Spotify session: no notification access");
        }
        return copy.getSessionToken();
    }

    private static synchronized void stop(Context c) {
        try {
            if (watching != null) c.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(watching);
        } catch (Throwable ignored) {
        }
        watching = null;
        follow(null);
        if (copy != null) copy.release();
        copy = null;
    }

    /** Spotify's playing session, else the first it has open. */
    private static MediaController spotifyOf(List<MediaController> sessions) {
        MediaController first = null;
        for (MediaController s : sessions == null ? new ArrayList<MediaController>() : sessions) {
            if (!MusicApps.SPOTIFY.equals(s.getPackageName())) continue;
            PlaybackState p = s.getPlaybackState();
            if (p != null && p.getState() == PlaybackState.STATE_PLAYING) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private static synchronized void follow(MediaController s) {
        if (s != null && spotify != null && s.getSessionToken().equals(spotify.getSessionToken())) return;
        if (spotify != null && following != null) spotify.unregisterCallback(following);
        spotify = s;
        following = null;
        if (copy == null) return;
        if (s == null) {
            copy.setMetadata(NOTHING);
            copy.setPlaybackState(STOPPED);
            return;
        }
        following = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                if (copy != null && spotify == s) copy.setMetadata(metadata != null ? metadata : NOTHING);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (copy != null && spotify == s) copy.setPlaybackState(state != null ? state : STOPPED);
            }

            @Override
            public void onExtrasChanged(Bundle extras) {
                if (copy != null && spotify == s) copy.setExtras(extras);
            }

            @Override
            public void onSessionDestroyed() {
                if (spotify == s) follow(null);
            }
        };
        s.registerCallback(following, main);
        MediaMetadata m = s.getMetadata();
        PlaybackState p = s.getPlaybackState();
        copy.setMetadata(m != null ? m : NOTHING);
        copy.setPlaybackState(p != null ? p : STOPPED);
        copy.setExtras(s.getExtras());
        Log.i(TAG, "Waze Spotify session: following Spotify's, "
                + (m == null ? "nothing" : m.getString(MediaMetadata.METADATA_KEY_TITLE)) + ", state " + (p == null ? "none" : p.getState()));
    }

    /** Waze's player's commands, for Spotify's session. */
    private static final class Forward extends MediaSession.Callback {
        private static MediaController.TransportControls to() {
            MediaController s = spotify;
            return s == null ? null : s.getTransportControls();
        }

        @Override public void onPlay() { MediaController.TransportControls t = to(); if (t != null) t.play(); }
        @Override public void onPause() { MediaController.TransportControls t = to(); if (t != null) t.pause(); }
        @Override public void onStop() { MediaController.TransportControls t = to(); if (t != null) t.stop(); }
        @Override public void onSkipToNext() { MediaController.TransportControls t = to(); if (t != null) t.skipToNext(); }
        @Override public void onSkipToPrevious() { MediaController.TransportControls t = to(); if (t != null) t.skipToPrevious(); }
        @Override public void onFastForward() { MediaController.TransportControls t = to(); if (t != null) t.fastForward(); }
        @Override public void onRewind() { MediaController.TransportControls t = to(); if (t != null) t.rewind(); }
        @Override public void onSeekTo(long pos) { MediaController.TransportControls t = to(); if (t != null) t.seekTo(pos); }
        @Override public void onSkipToQueueItem(long id) { MediaController.TransportControls t = to(); if (t != null) t.skipToQueueItem(id); }
        @Override public void onSetRating(Rating rating) { MediaController.TransportControls t = to(); if (t != null) t.setRating(rating); }
        @Override public void onPlayFromMediaId(String id, Bundle extras) {
            MediaController.TransportControls t = to();
            if (t != null) t.playFromMediaId(id, extras);
        }
        @Override public void onPlayFromSearch(String query, Bundle extras) {
            MediaController.TransportControls t = to();
            if (t != null) t.playFromSearch(query, extras);
        }
        @Override public void onPlayFromUri(Uri uri, Bundle extras) {
            MediaController.TransportControls t = to();
            if (t != null) t.playFromUri(uri, extras);
        }
        @Override public void onCustomAction(String action, Bundle extras) {
            MediaController.TransportControls t = to();
            if (t != null) t.sendCustomAction(action, extras);
        }
    }
}
