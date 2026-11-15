package org.ungoogled.waze;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Patched YouTube Music (issue #201): Waze's audio player offers only the apps on its server's partner list
 * (SdkConfiguration.updateAppList, from configGetSdkConfigurationNTV), and lets an app connect to its SdkService
 * only when the caller's package name is on that list. YouTube Music patched with Morphe or ReVanced is the same
 * app with the same Waze audio SDK inside, under another package name (app.morphe.android.apps.youtube.music,
 * app.revanced.android.apps.youtube.music, or whatever the user chose), so Waze calls it "not installed".
 *
 * Here every installed app that answers Waze's audio SDK broadcast but is not on the list is added to it when its
 * receiver still lives under a listed partner's package name -- a renamed partner keeps its class names -- with that
 * partner's scope, and the app's own label. YouTube Music is added even when the list has no entry for it, as an
 * audio app only. Nothing is sent anywhere: the list is Waze's local copy, and the partner's icon is fetched under
 * the partner's own name, as Waze does for it.
 *
 * Idea from Pilotkosinus/morphe-waze-patches ("Patched YouTube Music Support", GPL-3.0).
 */
public final class MusicApps {
    private MusicApps() {}

    static final String FEATURE = "patchedYouTubeMusic";
    private static final String TAG = "UA";
    private static final String ACTION_INIT = "com.waze.sdk.audio.ACTION_INIT";
    private static final String YOUTUBE_MUSIC = "com.google.android.apps.youtube.music";
    /** SdkConfiguration.FEATURE_AUDIO: the scope bit of an audio partner. */
    private static final int FEATURE_AUDIO = 0x200000;
    private static final String CONFIG = "com.waze.sdk.SdkConfiguration";
    static final String ACTION_TEST = "org.ungoogled.waze.MUSIC_TEST";

    /** Added package -> the listed partner it is a renamed copy of. */
    private static final Map<String, String> partnerOf = new ConcurrentHashMap<>();
    private static volatile String lastAdded = "";

    // ---- start-up -------------------------------------------------------------------------

    /** From WazeMobileApplication.onCreate: refresh the list when an app is installed or removed. */
    public static void init(Application a) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(new Packages(), f, Context.RECEIVER_NOT_EXPORTED);
            else a.registerReceiver(new Packages(), f);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                IntentFilter t = new IntentFilter(ACTION_TEST);
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(new SelfTest(), t, Context.RECEIVER_EXPORTED);
                else a.registerReceiver(new SelfTest(), t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Waze music apps", t);
        }
    }

    // ---- the partner list -----------------------------------------------------------------

    /** SdkConfiguration.updateAppList, right after Waze read the partner list: adds the renamed partners to it. */
    public static void partners(Object config) {
        if (config == null) return;
        try {
            if (!Customization.on(FEATURE)) {
                partnerOf.clear();
                lastAdded = "";
                return;
            }
            Context c = Customization.appContext();
            if (c == null) return;
            Class<?> k = config.getClass();
            Field fPackages = k.getField("PackageNames"), fNames = k.getField("AppNames");
            Field fScopes = k.getField("Scopes"), fUrls = k.getField("InstallationUrls");
            String[] packages = (String[]) fPackages.get(config);
            String[] names = (String[]) fNames.get(config);
            int[] scopes = (int[]) fScopes.get(config);
            String[] urls = (String[]) fUrls.get(config);
            if (packages == null || names == null || scopes == null || urls == null) return;
            int n = Math.min(Math.min(packages.length, names.length), Math.min(scopes.length, urls.length));

            List<String> addPackages = new ArrayList<>(), addNames = new ArrayList<>();
            List<Integer> addScopes = new ArrayList<>();
            List<String> listed = Arrays.asList(packages).subList(0, n);
            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> found = pm.queryBroadcastReceivers(new Intent(ACTION_INIT), 0);
            for (ResolveInfo r : found == null ? new ArrayList<ResolveInfo>() : found) {
                ActivityInfo info = r.activityInfo;
                if (info == null || info.packageName == null || info.name == null) continue;
                String pkg = info.packageName;
                if (listed.contains(pkg) || addPackages.contains(pkg) || pkg.equals(c.getPackageName())) continue;
                // The listed partner whose package name the receiver class sits under (the longest such name).
                int partner = -1;
                for (int i = 0; i < n; i++) {
                    if (packages[i] != null && !packages[i].equals(pkg) && info.name.startsWith(packages[i] + ".")
                            && (partner < 0 || packages[i].length() > packages[partner].length())) partner = i;
                }
                String original;
                int scope;
                String fallbackName;
                if (partner >= 0) {
                    original = packages[partner];
                    scope = scopes[partner];
                    fallbackName = names[partner];
                } else if (info.name.startsWith(YOUTUBE_MUSIC + ".")) {
                    original = YOUTUBE_MUSIC;
                    scope = FEATURE_AUDIO;
                    fallbackName = "YouTube Music";
                } else {
                    continue;
                }
                if ((scope & FEATURE_AUDIO) == 0) continue;
                CharSequence label = null;
                try {
                    label = pm.getApplicationLabel(info.applicationInfo);
                } catch (Throwable ignored) {
                }
                addPackages.add(pkg);
                addNames.add(label != null && label.length() > 0 ? label.toString() : fallbackName);
                addScopes.add(scope);
                partnerOf.put(pkg, original);
            }
            for (String p : new ArrayList<>(partnerOf.keySet())) if (!addPackages.contains(p)) partnerOf.remove(p);
            String summary = addPackages.toString();
            if (!summary.equals(lastAdded)) {
                lastAdded = summary;
                StringBuilder sc = new StringBuilder();
                for (int v : addScopes) sc.append(" 0x").append(Integer.toHexString(v));
                if (!addPackages.isEmpty()) Log.i(TAG, "Waze music apps added: " + partnerOf + ", scopes" + sc);
            }
            if (addPackages.isEmpty()) return;

            int m = n + addPackages.size();
            String[] p2 = Arrays.copyOf(packages, m), n2 = Arrays.copyOf(names, m), u2 = Arrays.copyOf(urls, m);
            int[] s2 = Arrays.copyOf(scopes, m);
            for (int i = 0; i < addPackages.size(); i++) {
                p2[n + i] = addPackages.get(i);
                n2[n + i] = addNames.get(i);
                s2[n + i] = addScopes.get(i);
                u2[n + i] = "";   // only opened for an app that is not installed
            }
            fPackages.set(config, p2);
            fNames.set(config, n2);
            fScopes.set(config, s2);
            fUrls.set(config, u2);
        } catch (Throwable t) {
            Log.w(TAG, "Waze music apps", t);
        }
    }

    /** SdkConfiguration.getCustomizedAppIconUrl: Waze's server has the icon under the partner's own name. */
    public static String iconPackage(String pkg) {
        if (pkg == null) return null;
        String original = partnerOf.get(pkg);
        return original != null ? original : pkg;
    }

    /**
     * Reads the partner list again (Waze's own updateAppList), after an install or the switch changed. Waze only
     * re-reads it while its configuration is synced with the server, which never happens signed out, so the copy
     * it keeps is dropped first -- and put back if the native side has no list to give.
     */
    static void refresh() {
        try {
            Class<?> k = Class.forName(CONFIG);
            Method update = k.getDeclaredMethod("updateAppList");
            update.setAccessible(true);
            Field configs = null;
            try {
                configs = k.getDeclaredField("appConfigs");
                configs.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // renamed: Waze's own re-read only
            }
            synchronized (k) {   // updateAppList is static synchronized
                Object kept = configs != null ? configs.get(null) : null;
                if (configs != null && kept == null) return;   // not read yet: the hook runs when Waze reads it
                if (configs != null) configs.set(null, null);
                update.invoke(null);
                if (configs != null && configs.get(null) == null) configs.set(null, kept);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Waze music apps: refresh", t);
        }
    }

    private static final class Packages extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Customization.on(FEATURE) || !partnerOf.isEmpty()) refresh();
        }
    }

    // ---- self-test (log.tag.UA DEBUG only) --------------------------------------------------

    /**
     * am broadcast -a org.ungoogled.waze.MUSIC_TEST [--es connect <package>] [--ez settings true]
     * [--es browse <package> [--ez play true] [--es control pause|play|next]]:
     * logs the audio apps Waze offers now (SdkConfiguration.getPartnerAudioApps); connect asks that app to connect
     * as Waze does when it is picked; settings opens Waze's own Audio player settings; browse lists what the app
     * offers Waze (its media browser root, as Waze sees it), play starts the first playable item and control sends
     * a transport control, all from Waze's process.
     */
    private static final class SelfTest extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                refresh();
                Class<?> k = Class.forName(CONFIG);
                Method list = k.getDeclaredMethod("getPartnerAudioApps", Context.class, boolean.class);
                list.setAccessible(true);
                List<?> apps = (List<?>) list.invoke(null, context.getApplicationContext(), false);
                StringBuilder sb = new StringBuilder();
                for (Object app : apps) {
                    List<String> strings = new ArrayList<>();
                    for (Field f : app.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != String.class && f.getType() != int.class) continue;
                        f.setAccessible(true);
                        Object v = f.get(app);
                        strings.add(v instanceof Integer ? "0x" + Integer.toHexString((Integer) v) : String.valueOf(v));
                    }
                    sb.append(strings).append(' ');
                }
                Log.i(TAG, "Waze music apps offered: " + apps.size() + " " + sb + "added " + partnerOf);
                String connect = intent.getStringExtra("connect");
                if (connect != null) {
                    // What Waze sends when an audio app is picked (SDK audio ACTION_INIT, no token yet).
                    Intent init = new Intent(ACTION_INIT).setPackage(connect)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    init.putExtra("token", (String) null);
                    context.getApplicationContext().sendBroadcast(init);
                    Log.i(TAG, "Waze music apps: asked " + connect + " to connect");
                }
                if (intent.getBooleanExtra("settings", false)) {
                    Method open = k.getDeclaredMethod("openAudioSdkSettingsScreen");
                    open.setAccessible(true);
                    open.invoke(null);
                    Log.i(TAG, "Waze music apps: opened the audio player settings");
                }
                String browse = intent.getStringExtra("browse");
                if (browse != null) Browse.start(context.getApplicationContext(), browse,
                        intent.getBooleanExtra("play", false), intent.getStringExtra("control"));
            } catch (Throwable t) {
                Log.w(TAG, "Waze music apps: self-test", t);
            }
        }
    }

    /** The self-test's media browser: Waze's identity, the framework's MediaBrowser. */
    private static final class Browse {
        static android.media.browse.MediaBrowser browser;

        static void start(Context c, String pkg, boolean play, String control) {
            Intent i = new Intent("android.media.browse.MediaBrowserService").setPackage(pkg);
            List<ResolveInfo> found = c.getPackageManager().queryIntentServices(i, 0);
            if (found == null || found.isEmpty()) {
                Log.i(TAG, "Waze music apps: " + pkg + " has no media browser");
                return;
            }
            android.content.ComponentName name = new android.content.ComponentName(pkg, found.get(0).serviceInfo.name);
            if (browser != null) browser.disconnect();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            browser = new android.media.browse.MediaBrowser(c, name, new android.media.browse.MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    final android.media.browse.MediaBrowser b = browser;
                    final android.media.session.MediaController mc = new android.media.session.MediaController(c, b.getSessionToken());
                    Log.i(TAG, "Waze music apps: browser connected to " + pkg + ", root " + b.getRoot());
                    if (control != null) {
                        android.media.session.MediaController.TransportControls t = mc.getTransportControls();
                        if ("pause".equals(control)) t.pause();
                        else if ("next".equals(control)) t.skipToNext();
                        else t.play();
                        Log.i(TAG, "Waze music apps: sent " + control);
                        h.postDelayed(() -> state(mc), 8000);
                        return;
                    }
                    b.subscribe(b.getRoot(), new android.media.browse.MediaBrowser.SubscriptionCallback() {
                        boolean played;

                        @Override
                        public void onChildrenLoaded(String parent, List<android.media.browse.MediaBrowser.MediaItem> items) {
                            StringBuilder sb = new StringBuilder();
                            for (android.media.browse.MediaBrowser.MediaItem it : items) {
                                sb.append(it.isPlayable() ? "[play] " : "[list] ").append(it.getDescription().getTitle()).append("; ");
                            }
                            Log.i(TAG, "Waze music apps: " + parent + " has " + items.size() + ": " + sb);
                            for (android.media.browse.MediaBrowser.MediaItem it : items) {
                                if (it.isPlayable() && play && !played) {
                                    played = true;
                                    mc.getTransportControls().playFromMediaId(it.getMediaId(), null);
                                    Log.i(TAG, "Waze music apps: play " + it.getDescription().getTitle());
                                    h.postDelayed(() -> state(mc), 15000);
                                    return;
                                }
                            }
                            for (android.media.browse.MediaBrowser.MediaItem it : items) {
                                if (it.isBrowsable() && parent.equals(b.getRoot())) {
                                    b.subscribe(it.getMediaId(), this);
                                    return;
                                }
                            }
                        }

                        @Override
                        public void onError(String parent) {
                            Log.i(TAG, "Waze music apps: browse error for " + parent);
                        }
                    });
                }

                @Override
                public void onConnectionFailed() {
                    Log.i(TAG, "Waze music apps: browser refused by " + pkg);
                }
            }, null);
            browser.connect();
        }

        static void state(android.media.session.MediaController mc) {
            android.media.session.PlaybackState s = mc.getPlaybackState();
            android.media.MediaMetadata m = mc.getMetadata();
            Log.i(TAG, "Waze music apps: state " + (s == null ? "none" : s.getState() + " at " + s.getPosition()
                    + " error " + s.getErrorMessage()) + ", playing "
                    + (m == null ? "nothing" : m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) + " / "
                    + m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)));
        }
    }
}
