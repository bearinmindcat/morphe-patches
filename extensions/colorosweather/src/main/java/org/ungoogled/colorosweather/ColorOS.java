package org.ungoogled.colorosweather;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What OnePlus Weather's ColorOS stand-ins answer through (generated from .github/scripts/coloros-standins/runtime):
 * the phone's own system properties for ColorOS's SystemProperties wrappers, the Android object a ColorOS graphics
 * wrapper (OplusCanvas, OplusPath, OplusOutline) was made around, and stand-ins of ColorOS's interfaces whose calls
 * answer "absent". On a ColorOS phone the framework's own classes load first, so none of this runs there.
 */
public final class ColorOS {
    private static Method get;
    private static final Map<Object, Object> WRAPPED = Collections.synchronizedMap(new WeakHashMap<>());

    private ColorOS() {
    }

    /** The phone's own value, or def where it has none (Android's hidden SystemProperties, by reflection). */
    public static String prop(String key, String def) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = key == null ? null : get.invoke(null, key, "");
            return value == null || ((String) value).isEmpty() ? def : (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return def;
        }
    }

    public static int propInt(String key, int def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long propLong(String key, long def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Long.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean propBoolean(String key, boolean def) {
        String value = prop(key, "");
        switch (value) {
            case "1": case "y": case "yes": case "on": case "true": return true;
            case "0": case "n": case "no": case "off": case "false": return false;
            default: return def;
        }
    }

    /** Remembers the Android object a ColorOS wrapper stand-in was constructed around. */
    public static void wrap(Object wrapper, Object wrapped) {
        if (wrapper != null && wrapped != null) WRAPPED.put(wrapper, wrapped);
    }

    /** The Android object [wrapper] was constructed around, or null. */
    public static <T> T wrapped(Object wrapper, Class<T> type) {
        Object value = WRAPPED.get(wrapper);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * A broadcast receiver the app registers at run time without saying whether other apps may send to it (fine for
     * ColorOS's system apps; an installed app targeting Android 14+ gets a SecurityException): exported, as Android
     * registered it before Android 14 (patches/.../coloros/ColorOsReceivers.kt sends the calls here).
     */
    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
        return context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler) {
        return context.registerReceiver(receiver, filter, permission, handler, android.content.Context.RECEIVER_EXPORTED);
    }

    /** The same with flags: kept when they say exported or not, else exported. */
    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, int flags) {
        return context.registerReceiver(receiver, filter, exported(flags));
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler, int flags) {
        return context.registerReceiver(receiver, filter, permission, handler, exported(flags));
    }

    private static int exported(int flags) {
        int either = android.content.Context.RECEIVER_EXPORTED | android.content.Context.RECEIVER_NOT_EXPORTED;
        return (flags & either) == 0 ? flags | android.content.Context.RECEIVER_EXPORTED : flags;
    }

    /*
     * Another app's service or provider that refuses the app. On an OPPO / OnePlus / realme phone the ColorOS apps it
     * talks to (phone manager, account, cloud, ...) are installed but refuse an app OPPO didn't sign, with a
     * SecurityException the app doesn't expect from them; elsewhere they are missing, which it handles. A refusal from
     * another app's service, or from a provider under OPPO's names, answers as Android answers for a missing one
     * (patches/.../coloros/ColorOsServices.kt sends the calls here). The app's own components never refuse it.
     */

    private static final String[] OPPO_NAMES = { "com.coloros.", "com.oplus.", "com.oplusos.", "com.heytap.",
            "com.nearme.", "com.oppo.", "com.realme.", "com.oneplus.", "com.opos.", "com.color.", "oplus.", "oppo.",
            "heytap." };

    /** The service is another app's: the refusal reads as "not installed". */
    private static boolean foreign(android.content.Context context, android.content.Intent intent, SecurityException e) {
        String target = intent == null ? null : intent.getPackage();
        if (target == null && intent != null && intent.getComponent() != null) target = intent.getComponent().getPackageName();
        if (target == null || target.equals(context.getPackageName())) return false;
        android.util.Log.w("ColorOS", "refused, treated as missing: " + intent + ": " + e.getMessage());
        return true;
    }

    /** The provider has one of OPPO's names: the refusal reads as "no such provider". */
    private static boolean oppo(String authority, SecurityException e) {
        if (authority == null) return false;
        for (String name : OPPO_NAMES) {
            if (authority.startsWith(name)) {
                android.util.Log.w("ColorOS", "refused, treated as missing: " + authority + ": " + e.getMessage());
                return true;
            }
        }
        return false;
    }

    private static boolean oppo(android.net.Uri uri, SecurityException e) {
        return uri != null && oppo(uri.getAuthority(), e);
    }

    public static boolean bindService(android.content.Context context, android.content.Intent service,
            android.content.ServiceConnection connection, int flags) {
        try {
            return context.bindService(service, connection, flags);
        } catch (SecurityException e) {
            if (foreign(context, service, e)) return false;
            throw e;
        }
    }

    public static boolean bindService(android.content.Context context, android.content.Intent service, int flags,
            java.util.concurrent.Executor executor, android.content.ServiceConnection connection) {
        try {
            return context.bindService(service, flags, executor, connection);
        } catch (SecurityException e) {
            if (foreign(context, service, e)) return false;
            throw e;
        }
    }

    public static android.content.ComponentName startService(android.content.Context context,
            android.content.Intent service) {
        try {
            return context.startService(service);
        } catch (SecurityException e) {
            if (foreign(context, service, e)) return null;
            throw e;
        }
    }

    public static android.content.ComponentName startForegroundService(android.content.Context context,
            android.content.Intent service) {
        try {
            return context.startForegroundService(service);
        } catch (SecurityException e) {
            if (foreign(context, service, e)) return null;
            throw e;
        }
    }

    public static boolean stopService(android.content.Context context, android.content.Intent service) {
        try {
            return context.stopService(service);
        } catch (SecurityException e) {
            if (foreign(context, service, e)) return false;
            throw e;
        }
    }

    public static android.database.Cursor query(android.content.ContentResolver resolver, android.net.Uri uri,
            String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static android.database.Cursor query(android.content.ContentResolver resolver, android.net.Uri uri,
            String[] projection, String selection, String[] selectionArgs, String sortOrder,
            android.os.CancellationSignal cancellationSignal) {
        try {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder, cancellationSignal);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static android.database.Cursor query(android.content.ContentResolver resolver, android.net.Uri uri,
            String[] projection, android.os.Bundle queryArgs, android.os.CancellationSignal cancellationSignal) {
        try {
            return resolver.query(uri, projection, queryArgs, cancellationSignal);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static String getType(android.content.ContentResolver resolver, android.net.Uri uri) {
        try {
            return resolver.getType(uri);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static android.net.Uri insert(android.content.ContentResolver resolver, android.net.Uri uri,
            android.content.ContentValues values) {
        try {
            return resolver.insert(uri, values);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URL " + uri);
            throw e;
        }
    }

    public static android.net.Uri insert(android.content.ContentResolver resolver, android.net.Uri uri,
            android.content.ContentValues values, android.os.Bundle extras) {
        try {
            return resolver.insert(uri, values, extras);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URL " + uri);
            throw e;
        }
    }

    public static int bulkInsert(android.content.ContentResolver resolver, android.net.Uri uri,
            android.content.ContentValues[] values) {
        try {
            return resolver.bulkInsert(uri, values);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URL " + uri);
            throw e;
        }
    }

    public static int update(android.content.ContentResolver resolver, android.net.Uri uri,
            android.content.ContentValues values, String where, String[] selectionArgs) {
        try {
            return resolver.update(uri, values, where, selectionArgs);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URI " + uri);
            throw e;
        }
    }

    public static int update(android.content.ContentResolver resolver, android.net.Uri uri,
            android.content.ContentValues values, android.os.Bundle extras) {
        try {
            return resolver.update(uri, values, extras);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URI " + uri);
            throw e;
        }
    }

    public static int delete(android.content.ContentResolver resolver, android.net.Uri uri, String where,
            String[] selectionArgs) {
        try {
            return resolver.delete(uri, where, selectionArgs);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URL " + uri);
            throw e;
        }
    }

    public static int delete(android.content.ContentResolver resolver, android.net.Uri uri, android.os.Bundle extras) {
        try {
            return resolver.delete(uri, extras);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URL " + uri);
            throw e;
        }
    }

    public static android.os.Bundle call(android.content.ContentResolver resolver, android.net.Uri uri, String method,
            String arg, android.os.Bundle extras) {
        try {
            return resolver.call(uri, method, arg, extras);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new IllegalArgumentException("Unknown URI " + uri);
            throw e;
        }
    }

    public static android.os.Bundle call(android.content.ContentResolver resolver, String authority, String method,
            String arg, android.os.Bundle extras) {
        try {
            return resolver.call(authority, method, arg, extras);
        } catch (SecurityException e) {
            if (oppo(authority, e)) throw new IllegalArgumentException("Unknown authority " + authority);
            throw e;
        }
    }

    public static android.content.ContentProviderResult[] applyBatch(android.content.ContentResolver resolver,
            String authority, java.util.ArrayList<android.content.ContentProviderOperation> operations)
            throws android.os.RemoteException, android.content.OperationApplicationException {
        try {
            return resolver.applyBatch(authority, operations);
        } catch (SecurityException e) {
            if (oppo(authority, e)) throw new IllegalArgumentException("Unknown authority " + authority);
            throw e;
        }
    }

    public static android.content.ContentProviderClient acquireContentProviderClient(
            android.content.ContentResolver resolver, android.net.Uri uri) {
        try {
            return resolver.acquireContentProviderClient(uri);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static android.content.ContentProviderClient acquireContentProviderClient(
            android.content.ContentResolver resolver, String name) {
        try {
            return resolver.acquireContentProviderClient(name);
        } catch (SecurityException e) {
            if (oppo(name, e)) return null;
            throw e;
        }
    }

    public static android.content.ContentProviderClient acquireUnstableContentProviderClient(
            android.content.ContentResolver resolver, android.net.Uri uri) {
        try {
            return resolver.acquireUnstableContentProviderClient(uri);
        } catch (SecurityException e) {
            if (oppo(uri, e)) return null;
            throw e;
        }
    }

    public static android.content.ContentProviderClient acquireUnstableContentProviderClient(
            android.content.ContentResolver resolver, String name) {
        try {
            return resolver.acquireUnstableContentProviderClient(name);
        } catch (SecurityException e) {
            if (oppo(name, e)) return null;
            throw e;
        }
    }

    public static java.io.InputStream openInputStream(android.content.ContentResolver resolver, android.net.Uri uri)
            throws java.io.FileNotFoundException {
        try {
            return resolver.openInputStream(uri);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static java.io.OutputStream openOutputStream(android.content.ContentResolver resolver, android.net.Uri uri)
            throws java.io.FileNotFoundException {
        try {
            return resolver.openOutputStream(uri);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static java.io.OutputStream openOutputStream(android.content.ContentResolver resolver, android.net.Uri uri,
            String mode) throws java.io.FileNotFoundException {
        try {
            return resolver.openOutputStream(uri, mode);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static android.os.ParcelFileDescriptor openFileDescriptor(android.content.ContentResolver resolver,
            android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            return resolver.openFileDescriptor(uri, mode);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static android.os.ParcelFileDescriptor openFileDescriptor(android.content.ContentResolver resolver,
            android.net.Uri uri, String mode, android.os.CancellationSignal signal) throws java.io.FileNotFoundException {
        try {
            return resolver.openFileDescriptor(uri, mode, signal);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static android.content.res.AssetFileDescriptor openAssetFileDescriptor(
            android.content.ContentResolver resolver, android.net.Uri uri, String mode)
            throws java.io.FileNotFoundException {
        try {
            return resolver.openAssetFileDescriptor(uri, mode);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static android.content.res.AssetFileDescriptor openAssetFileDescriptor(
            android.content.ContentResolver resolver, android.net.Uri uri, String mode,
            android.os.CancellationSignal signal) throws java.io.FileNotFoundException {
        try {
            return resolver.openAssetFileDescriptor(uri, mode, signal);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    public static android.content.res.AssetFileDescriptor openTypedAssetFileDescriptor(
            android.content.ContentResolver resolver, android.net.Uri uri, String mimeType, android.os.Bundle opts)
            throws java.io.FileNotFoundException {
        try {
            return resolver.openTypedAssetFileDescriptor(uri, mimeType, opts);
        } catch (SecurityException e) {
            if (oppo(uri, e)) throw new java.io.FileNotFoundException("No content provider: " + uri);
            throw e;
        }
    }

    /**
     * A stand-in of a ColorOS interface (a system service's binder interface, ...) whose calls answer "absent"
     * (false / 0 / null), with Android's own answers for the few that have one.
     */
    public static <T> T absent(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getInitialDisplayDensity": return android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
                case "hasNavigationBar": return true;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return args != null && args.length == 1 && proxy == args[0];
                case "toString": return type.getName() + " (absent off ColorOS)";
                default: break;
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == float.class) return 0f;
            if (r == double.class) return 0d;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            if (r == char.class) return (char) 0;
            return null;
        }));
    }
}
