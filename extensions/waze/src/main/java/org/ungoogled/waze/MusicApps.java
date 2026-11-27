package org.ungoogled.waze;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * Any media app (issues #240, #233): every other media app for Android Auto (a media browser service, and Android
 * Auto's description in its manifest using "media") is added too, as an audio app, and counts as installed although
 * it has no SDK to answer the broadcast. When it is picked, its session is opened in Waze the way Waze's SDK service
 * opens a partner's when the partner connects, so Waze's own media browser does the rest: track, artwork,
 * play/pause/skip and browsing.
 *
 * Spotify (issue #213) reaches Waze through its own SDK or App Remote, which Spotify grants only to Waze signed by
 * Waze, so a re-signed Waze showed "Nothing is playing". There Spotify takes the media app route too, and when its
 * media browser refuses Waze, Waze's player is handed Waze's own copy of Spotify's media session (SpotifySession).
 * A root mount install keeps Waze's signature and Spotify's own route.
 *
 * Idea from Pilotkosinus/morphe-waze-patches ("Patched YouTube Music Support", GPL-3.0).
 */
public final class MusicApps {
    private MusicApps() {}

    static final String FEATURE = "patchedYouTubeMusic";
    static final String MEDIA_FEATURE = "anyMediaApp";
    private static final String TAG = "UA";
    private static final String ACTION_INIT = "com.waze.sdk.audio.ACTION_INIT";
    private static final String YOUTUBE_MUSIC = "com.google.android.apps.youtube.music";
    static final String SPOTIFY = "com.spotify.music";
    /** An action nothing receives. */
    private static final String ACTION_NOBODY = "org.ungoogled.waze.NOBODY";
    private static final String MEDIA_BROWSER = "android.media.browse.MediaBrowserService";
    /** Android Auto's marker in an app's manifest. */
    private static final String CAR_APP = "com.google.android.gms.car.application";
    /** SdkConfiguration.FEATURE_AUDIO: the scope bit of an audio partner. */
    private static final int FEATURE_AUDIO = 0x200000;
    private static final String CONFIG = "com.waze.sdk.SdkConfiguration";
    static final String ACTION_TEST = "org.ungoogled.waze.MUSIC_TEST";
    /** Waze's signing certificate (SHA-256): Play Store installs and root mount installs have it. */
    private static final String WAZE_CERT = "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7";
    /** Spotify refused Waze's media browser (kept across starts, so Waze goes to its session at once). */
    private static final String KEY_SPOTIFY_REFUSED = "spotify_refused";

    /** Added package -> the listed partner it is a renamed copy of. */
    private static final Map<String, String> partnerOf = new ConcurrentHashMap<>();
    /** Media apps added to the list (no Waze audio SDK in them). */
    private static volatile Set<String> mediaApps = Collections.emptySet();
    private static volatile String lastAdded = "";
    private static final Handler main = new Handler(Looper.getMainLooper());

    // Waze's audio SDK classes, renamed every build: the patch writes their names here.
    /** The audio session manager (bd). */
    static String sessionsClass() { return ""; }
    /** Its instance (static, no arguments). */
    static String sessionsInstance() { return ""; }
    /** Its connect(Resources, package, token, Bundle, Messenger), what Waze's SDK service calls for a partner. */
    static String sessionsConnect() { return ""; }
    /** Waze's players by package (a/j): its instance's remove(package) drops one without disconnecting it. */
    static String playersClass() { return ""; }
    static String playersRemove() { return ""; }
    /** The audio apps' start-up (ap). */
    static String pickerClass() { return ""; }
    /** Its pick(package, token, Runnable timeout), what Waze's audio panel calls when an app is tapped; reconnects. */
    static String pickerPick() { return ""; }

    private static boolean ytmOn() {
        return Customization.patched(FEATURE) && Customization.on(FEATURE);
    }

    private static boolean mediaOn() {
        return Customization.patched(MEDIA_FEATURE) && Customization.on(MEDIA_FEATURE);
    }

    private static volatile Boolean wazeSigned;

    /** Whether this Waze carries Waze's own signature (Spotify then knows it). */
    private static boolean wazeSigned(Context c) {
        Boolean known = wazeSigned;
        if (known != null) return known;
        boolean found = false;
        try {
            android.content.pm.SigningInfo info = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            android.content.pm.Signature[] certs = info.hasMultipleSigners()
                    ? info.getApkContentsSigners() : info.getSigningCertificateHistory();
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature cert : certs) {
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest(cert.toByteArray())) hex.append(String.format("%02x", b));
                if (WAZE_CERT.contentEquals(hex)) found = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Waze music apps: signature", t);
        }
        return wazeSigned = found;
    }

    /** Spotify goes the media app route: Any media app is on and Waze is not Waze-signed. */
    private static boolean spotifyHere() {
        if (!mediaOn()) return false;
        Context c = Customization.appContext();
        return c != null && !wazeSigned(c);
    }

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
            boolean ytm = ytmOn(), media = mediaOn();
            if (!ytm) partnerOf.clear();
            if (!media) mediaApps = Collections.emptySet();
            if (!ytm && !media) {
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
            Set<String> withSdk = new HashSet<>();
            for (ResolveInfo r : found == null ? new ArrayList<ResolveInfo>() : found) {
                ActivityInfo info = r.activityInfo;
                if (info == null || info.packageName == null || info.name == null) continue;
                String pkg = info.packageName;
                withSdk.add(pkg);
                if (!ytm) continue;
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
                addPackages.add(pkg);
                addNames.add(label(pm, info.applicationInfo, fallbackName));
                addScopes.add(scope);
                partnerOf.put(pkg, original);
            }
            for (String p : new ArrayList<>(partnerOf.keySet())) if (!addPackages.contains(p)) partnerOf.remove(p);

            // Any media app: the apps with a media browser service but no audio SDK, as audio apps only.
            Set<String> media2 = new HashSet<>();
            if (media) {
                List<ResolveInfo> browsers = pm.queryIntentServices(new Intent(MEDIA_BROWSER), PackageManager.GET_META_DATA);
                for (ResolveInfo r : browsers == null ? new ArrayList<ResolveInfo>() : browsers) {
                    if (r.serviceInfo == null || r.serviceInfo.applicationInfo == null) continue;
                    String pkg = r.serviceInfo.packageName;
                    // Google's own media browsers answer only Google's apps (the Google app's refuses Waze).
                    if (pkg == null || listed.contains(pkg) || addPackages.contains(pkg) || withSdk.contains(pkg)
                            || pkg.equals(c.getPackageName()) || pkg.equals(SPOTIFY) || pkg.startsWith("com.google.android.")
                            || !mediaApp(pm, pkg)) continue;
                    addPackages.add(pkg);
                    addNames.add(label(pm, r.serviceInfo.applicationInfo, pkg));
                    addScopes.add(FEATURE_AUDIO);
                    media2.add(pkg);
                }
            }
            mediaApps = Collections.unmodifiableSet(media2);

            // Spotify through its session: an audio app only, since nothing of Waze's goes to Spotify (no data
            // sharing question); listed when Waze's server did not list it.
            if (media && spotifyHere()) {
                int at = listed.indexOf(SPOTIFY);
                if (at >= 0) {
                    scopes[at] = FEATURE_AUDIO;
                } else if (!addPackages.contains(SPOTIFY)) {
                    try {
                        android.content.pm.ApplicationInfo info = pm.getApplicationInfo(SPOTIFY, 0);
                        addPackages.add(SPOTIFY);
                        addNames.add(label(pm, info, "Spotify"));
                        addScopes.add(FEATURE_AUDIO);
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // not installed
                    }
                }
            }

            String summary = addPackages.toString();
            if (!summary.equals(lastAdded)) {
                lastAdded = summary;
                StringBuilder sc = new StringBuilder();
                for (int v : addScopes) sc.append(" 0x").append(Integer.toHexString(v));
                if (!addPackages.isEmpty()) Log.i(TAG, "Waze music apps added: " + partnerOf + ", media apps " + media2 + ", scopes" + sc);
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

    private static String label(PackageManager pm, android.content.pm.ApplicationInfo info, String fallback) {
        try {
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * A media app for Android Auto: its manifest points Android Auto's marker at a description that uses "media"
     * (what Android Auto itself lists). Media browsers of other kinds -- Bluetooth's, or ones only Google's own apps
     * may connect to -- are left out.
     */
    private static boolean mediaApp(PackageManager pm, String pkg) {
        try {
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            int desc = info.metaData == null ? 0 : info.metaData.getInt(CAR_APP, 0);
            if (desc == 0) return false;
            android.content.res.XmlResourceParser x = pm.getResourcesForApplication(info).getXml(desc);
            try {
                for (int e = x.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; e = x.next()) {
                    if (e == org.xmlpull.v1.XmlPullParser.START_TAG && "uses".equals(x.getName())
                            && "media".equals(x.getAttributeValue(null, "name"))) return true;
                }
            } finally {
                x.close();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---- Any media app: installed, and connected when picked ----------------------------------

    /** The audio SDK's "has a receiver for ACTION_INIT" (Waze's installed check): a media app has one. */
    public static boolean receives(Intent intent) {
        try {
            if (intent == null || !ACTION_INIT.equals(intent.getAction())) return false;
            String pkg = intent.getPackage();
            return pkg != null && (mediaApps.contains(pkg) && mediaOn() || SPOTIFY.equals(pkg) && spotifyHere());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Waze sent the audio SDK's ACTION_INIT (a tap in the audio panel, or the last app reconnected at start) and
     * opened its media browser to the app. A partner's SDK answers by connecting to Waze's SDK service with the
     * token; a media app has no SDK, so its session is connected here, after Waze set up its timeout.
     */
    public static void started(String pkg, Intent intent) {
        try {
            if (pkg == null || intent == null) return;
            boolean spotify = SPOTIFY.equals(pkg) && spotifyHere();
            if (!spotify && !(mediaApps.contains(pkg) && mediaOn())) return;
            // Spotify's own Waze SDK is left out (it would open a second session): the broadcast goes nowhere.
            if (spotify) intent.setAction(ACTION_NOBODY).setPackage(Customization.appContext().getPackageName());
            // Waze keeps one player per app; one whose media browser refused it never retries, so it is dropped.
            if (refused.remove(pkg)) dropPlayer(pkg);
            final String token = intent.getStringExtra("token");
            main.post(() -> connect(pkg, token));
        } catch (Throwable t) {
            Log.w(TAG, "Waze media app", t);
        }
    }

    private static Messenger messenger;

    /** Where Waze's session sends the partner its messages; only a disconnect matters here. */
    private static synchronized Messenger messenger() {
        if (messenger == null) messenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == 502) Log.i(TAG, "Waze media app disconnected, reason "
                    + (msg.getData() == null ? "?" : msg.getData().getInt("reason")));
            return true;
        }));
        return messenger;
    }

    private static void connect(String pkg, String token) {
        try {
            Context c = Customization.appContext();
            if (c == null) return;
            Class<?> k = Class.forName(sessionsClass());
            Method instance = k.getDeclaredMethod(sessionsInstance());
            instance.setAccessible(true);
            Method connect = k.getDeclaredMethod(sessionsConnect(), Resources.class, String.class, String.class,
                    Bundle.class, Messenger.class);
            connect.setAccessible(true);
            Object ok = connect.invoke(instance.invoke(null), c.getResources(), pkg, token, new Bundle(), messenger());
            Log.i(TAG, "Waze media app " + pkg + (Boolean.TRUE.equals(ok) ? " connected" : " refused"));
            if (Boolean.TRUE.equals(ok)) probe(c, pkg, token != null, SPOTIFY.equals(pkg) && spotifyMirrored);
        } catch (Throwable t) {
            Log.w(TAG, "Waze media app " + pkg, t);
        }
    }

    /** Apps whose media browser refused Waze (their player in Waze is dead). */
    private static final Set<String> refused = ConcurrentHashMap.newKeySet();

    /**
     * Whether the app lets Waze browse it, asked as Waze's player asks (same app, same answer). Some apps let only
     * trusted controllers in; a pick by the user ([asked]) then says why, or asks for notification access. Spotify
     * refusing moves Waze's player to its session; [mirrored]: the player already is there, only remember the answer.
     */
    private static void probe(Context c, String pkg, boolean asked, boolean mirrored) {
        try {
            Intent i = new Intent(MEDIA_BROWSER).setPackage(pkg);
            List<ResolveInfo> found = c.getPackageManager().queryIntentServices(i, 0);
            if (found == null || found.isEmpty()) return;
            android.content.ComponentName name = new android.content.ComponentName(pkg, found.get(0).serviceInfo.name);
            final android.media.browse.MediaBrowser[] b = new android.media.browse.MediaBrowser[1];
            b[0] = new android.media.browse.MediaBrowser(c, name, new android.media.browse.MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    refused.remove(pkg);
                    if (SPOTIFY.equals(pkg)) spotifyRefused(c, false);
                    b[0].disconnect();
                }

                @Override
                public void onConnectionFailed() {
                    boolean access = MediaAccess.allowed(c);
                    Log.i(TAG, "Waze media app " + pkg + ": its media browser refused Waze, notification access " + access);
                    if (SPOTIFY.equals(pkg)) {
                        spotifyRefused(c, true);
                        if (mirrored) return;
                        refused.add(pkg);
                        // With access, Waze's player goes to Spotify's session; without, ask for it.
                        if (access) spotifyAgain();
                        else if (asked) MediaAccess.refused(label(c.getPackageManager(), pkg), true);
                        return;
                    }
                    refused.add(pkg);
                    if (asked) MediaAccess.refused(label(c.getPackageManager(), pkg), false);
                }
            }, null);
            b[0].connect();
        } catch (Throwable t) {
            Log.w(TAG, "Waze media app " + pkg, t);
        }
    }

    // ---- Spotify through its media session (issue #213) --------------------------------------

    /** Whether Waze's player for Spotify was handed its session (the last one made). */
    private static volatile boolean spotifyMirrored;
    private static long spotifyPicked;

    /** The audio pick's "is it Spotify" (Spotify's own SDK or App Remote): not when Spotify goes the media app route. */
    public static boolean spotifyRoute(boolean spotify) {
        try {
            return spotify && !spotifyHere();
        } catch (Throwable t) {
            return spotify;
        }
    }

    /**
     * Waze makes its player (a media browser) for an app: for Spotify that refuses Waze's media browser, the browser
     * Waze connects to is its own (SpotifySession), which hands over a copy of Spotify's session. Needs notification
     * access, which lets Waze see Spotify's session.
     */
    public static android.content.ComponentName browser(String pkg, android.content.ComponentName real) {
        try {
            if (!SPOTIFY.equals(pkg) || !spotifyHere()) return real;
            Context c = Customization.appContext();
            android.content.ComponentName own = new android.content.ComponentName(c, SpotifySession.class);
            boolean mirror = spotifyRefused(c) && MediaAccess.allowed(c) && declared(c, own);
            spotifyMirrored = mirror;
            if (!mirror) return real;
            Log.i(TAG, "Waze media app " + pkg + ": through its media session");
            return own;
        } catch (Throwable t) {
            Log.w(TAG, "Waze media app " + pkg, t);
            return real;
        }
    }

    private static boolean declared(Context c, android.content.ComponentName service) {
        try {
            c.getPackageManager().getServiceInfo(service, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean spotifyRefused(Context c) {
        return c.getSharedPreferences("ungoogled_waze", Context.MODE_PRIVATE).getBoolean(KEY_SPOTIFY_REFUSED, false);
    }

    private static void spotifyRefused(Context c, boolean refusedNow) {
        if (spotifyRefused(c) != refusedNow) {
            c.getSharedPreferences("ungoogled_waze", Context.MODE_PRIVATE).edit().putBoolean(KEY_SPOTIFY_REFUSED, refusedNow).apply();
        }
    }

    /** Spotify just refused Waze's player: pick it again (at most once in 10 s), now through its session. */
    private static void spotifyAgain() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - spotifyPicked < 10000) return;
        spotifyPicked = now;
        accessGranted();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return label(pm, pm.getApplicationInfo(pkg, 0), pkg);
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Drops Waze's player for [pkg], so the next one connects anew. */
    private static void dropPlayer(String pkg) {
        try {
            Class<?> k = Class.forName(playersClass());
            Object players = null;
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == k) {
                    f.setAccessible(true);
                    players = f.get(null);
                }
            }
            Method remove = k.getDeclaredMethod(playersRemove(), String.class);
            remove.setAccessible(true);
            remove.invoke(players, pkg);
        } catch (Throwable t) {
            Log.w(TAG, "Waze media app " + pkg, t);
        }
    }

    /**
     * Notification access was just allowed (the listener connected, or Waze is back from Android's page with it), or
     * Spotify refused Waze with access allowed: the app Waze is connected to that refused it is picked again, as a tap
     * in the audio panel would.
     */
    static void accessGranted() {
        main.post(() -> {
            try {
                Class<?> k = Class.forName(sessionsClass());
                Method instance = k.getDeclaredMethod(sessionsInstance());
                instance.setAccessible(true);
                Object sessions = instance.invoke(null);
                String current = null;
                for (String pkg : refused) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class
                                && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                            m.setAccessible(true);
                            if (pkg.equals(m.invoke(sessions))) current = pkg;
                        }
                    }
                }
                Log.i(TAG, "Waze media app refused Waze, reconnecting " + current);
                if (current != null) pick(current);
            } catch (Throwable t) {
                Log.w(TAG, "Waze media access", t);
            }
        });
    }

    /** Waze's own pick of [pkg] (the audio panel's tap), with a fresh token. */
    static boolean pick(String pkg) throws Exception {
        Class<?> k = Class.forName(pickerClass());
        Object picker = null;
        for (Field f : k.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == k) {
                f.setAccessible(true);
                picker = f.get(null);
            }
        }
        Method m = k.getDeclaredMethod(pickerPick(), String.class, String.class, Runnable.class);
        m.setAccessible(true);
        Object ok = m.invoke(picker, pkg, "ua-" + System.currentTimeMillis(),
                (Runnable) () -> Log.i(TAG, "Waze music apps: Waze gave up on " + pkg));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * SdkConfiguration.getCustomizedAppIconUrl: Waze's server has the icon under the partner's own name. A media
     * app has none there: null, and Waze shows the app's own icon without asking its server.
     */
    public static String iconPackage(String pkg) {
        if (pkg == null) return null;
        if (mediaApps.contains(pkg)) return null;
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
            if (ytmOn() || mediaOn() || !partnerOf.isEmpty() || !mediaApps.isEmpty()) refresh();
        }
    }

    // ---- self-test (log.tag.UA DEBUG only) --------------------------------------------------

    /**
     * am broadcast -a org.ungoogled.waze.MUSIC_TEST [--es connect <package>] [--ez settings true]
     * [--es browse <package> [--ez play true] [--es control pause|play|next]] [--es pick <package>]
     * [--es waze <method>] [--ez now true]:
     * logs the audio apps Waze offers now (SdkConfiguration.getPartnerAudioApps); connect asks that app to connect
     * as Waze does when it is picked; settings opens Waze's own Audio player settings; browse lists what the app
     * offers Waze (its media browser root, as Waze sees it), play starts the first playable item and control sends
     * a transport control, all from Waze's process. pick taps the app in Waze's audio panel (Waze's own method);
     * waze calls a no-argument method of Waze's player for the connected app (its media browser: in 5.24-5.27
     * g pause, h play, j next, k previous); now logs what Waze's player has: app, track and state.
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
                String pick = intent.getStringExtra("pick");
                if (pick != null) Waze.pick(pick);
                String call = intent.getStringExtra("waze");
                if (call != null) Waze.call(call);
                if (intent.getBooleanExtra("now", false) || call != null) {
                    final boolean later = call != null;
                    main.postDelayed(Waze::now, later ? 3000 : 0);
                }
                String browse = intent.getStringExtra("browse");
                if (browse != null) Browse.start(context.getApplicationContext(), browse,
                        intent.getBooleanExtra("play", false), intent.getStringExtra("control"));
            } catch (Throwable t) {
                Log.w(TAG, "Waze music apps: self-test", t);
            }
        }
    }

    /** The self-test's handles on Waze's own audio code (renamed classes, found from the names the patch wrote). */
    private static final class Waze {
        static void pick(String pkg) throws Exception {
            Log.i(TAG, "Waze music apps: picked " + pkg + " -> " + MusicApps.pick(pkg));
        }

        /** Waze's player for the connected app (the session manager's only method returning one), or null. */
        static Object player() throws Exception {
            Class<?> k = Class.forName(sessionsClass());
            Method instance = k.getDeclaredMethod(sessionsInstance());
            instance.setAccessible(true);
            Object sessions = instance.invoke(null);
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType().isInterface()
                        && m.getReturnType().getName().startsWith("com.waze.sdk.")
                        && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return m.invoke(sessions);
                }
            }
            return null;
        }

        static void call(String name) throws Exception {
            Object player = player();
            if (player == null) {
                Log.i(TAG, "Waze music apps: no player connected");
                return;
            }
            Method m = player.getClass().getMethod(name);
            m.setAccessible(true);
            m.invoke(player);
            Log.i(TAG, "Waze music apps: Waze's player " + name + "()");
        }

        static void now() {
            try {
                Class<?> k = Class.forName(sessionsClass());
                Method instance = k.getDeclaredMethod(sessionsInstance());
                instance.setAccessible(true);
                Object sessions = instance.invoke(null);
                String app = null;
                for (Method m : k.getDeclaredMethods()) {   // the connected app's package and name
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        app = (app == null ? "" : app + " / ") + m.invoke(sessions);
                    }
                }
                Object player = player();
                String track = "nothing", state = "none";
                if (player != null) {
                    for (Method m : player.getClass().getMethods()) {
                        if (m.getParameterTypes().length != 0) continue;
                        m.setAccessible(true);
                        String type = m.getReturnType().getName();
                        if (type.equals("android.support.v4.media.MediaMetadataCompat")) {
                            Object meta = m.invoke(player);
                            Bundle b = meta == null ? null : (Bundle) field(meta, Bundle.class);
                            if (b != null) track = b.getString("android.media.metadata.TITLE") + " / "
                                    + b.getString("android.media.metadata.ARTIST") + " / "
                                    + (b.containsKey("android.media.metadata.ALBUM_ART") || b.containsKey("android.media.metadata.ART")
                                    || b.containsKey("android.media.metadata.ALBUM_ART_URI") || b.containsKey("android.media.metadata.ART_URI")
                                    ? "artwork" : "no artwork");
                        } else if (type.startsWith("android.support.v4.media.") && !m.getReturnType().isPrimitive()
                                && m.getReturnType() != List.class) {
                            Object st = m.invoke(player);
                            Object fwk = st == null ? null : field(st, android.media.session.PlaybackState.class);
                            if (fwk != null) {
                                android.media.session.PlaybackState ps = (android.media.session.PlaybackState) fwk;
                                state = ps.getState() + " at " + ps.getPosition() + " actions 0x" + Long.toHexString(ps.getActions());
                            } else if (st != null) {
                                state = String.valueOf(st);
                            }
                        }
                    }
                }
                Log.i(TAG, "Waze music apps: Waze's player: app " + app + ", track " + track + ", state " + state);
            } catch (Throwable t) {
                Log.w(TAG, "Waze music apps: now", t);
            }
        }

        private static Object field(Object o, Class<?> type) throws Exception {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v != null) return v;
                    }
                }
            }
            return null;
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
