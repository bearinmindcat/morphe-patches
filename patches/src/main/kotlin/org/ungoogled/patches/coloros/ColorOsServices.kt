package org.ungoogled.patches.coloros

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import org.ungoogled.patches.samsung.redirectCalls

private const val INTENT = "Landroid/content/Intent;"
private const val URI = "Landroid/net/Uri;"
private const val STRING = "Ljava/lang/String;"
private const val STRINGS = "[Ljava/lang/String;"
private const val VALUES = "Landroid/content/ContentValues;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val CANCEL = "Landroid/os/CancellationSignal;"

/** Context's service calls (the runtime's method of the same name takes the Context first). */
private val SERVICE_CALLS = listOf(
    "bindService($INTENT" + "Landroid/content/ServiceConnection;I)Z",
    "bindService(${INTENT}ILjava/util/concurrent/Executor;Landroid/content/ServiceConnection;)Z",
    "startService($INTENT)Landroid/content/ComponentName;",
    "startForegroundService($INTENT)Landroid/content/ComponentName;",
    "stopService($INTENT)Z",
)

/** ContentResolver's calls that reach a provider (the runtime's method of the same name takes the resolver first). */
private val PROVIDER_CALLS = setOf(
    "query($URI$STRINGS$STRING$STRINGS$STRING)Landroid/database/Cursor;",
    "query($URI$STRINGS$STRING$STRINGS$STRING$CANCEL)Landroid/database/Cursor;",
    "query($URI$STRINGS$BUNDLE$CANCEL)Landroid/database/Cursor;",
    "getType($URI)$STRING",
    "insert($URI$VALUES)$URI",
    "insert($URI$VALUES$BUNDLE)$URI",
    "bulkInsert($URI[$VALUES)I",
    "update($URI$VALUES$STRING$STRINGS)I",
    "update($URI$VALUES$BUNDLE)I",
    "delete($URI$STRING$STRINGS)I",
    "delete($URI$BUNDLE)I",
    "call($URI$STRING$STRING$BUNDLE)$BUNDLE",
    "call($STRING$STRING$STRING$BUNDLE)$BUNDLE",
    "applyBatch(${STRING}Ljava/util/ArrayList;)[Landroid/content/ContentProviderResult;",
    "acquireContentProviderClient($URI)Landroid/content/ContentProviderClient;",
    "acquireContentProviderClient($STRING)Landroid/content/ContentProviderClient;",
    "acquireUnstableContentProviderClient($URI)Landroid/content/ContentProviderClient;",
    "acquireUnstableContentProviderClient($STRING)Landroid/content/ContentProviderClient;",
    "openInputStream($URI)Ljava/io/InputStream;",
    "openOutputStream($URI)Ljava/io/OutputStream;",
    "openOutputStream($URI$STRING)Ljava/io/OutputStream;",
    "openFileDescriptor($URI$STRING)Landroid/os/ParcelFileDescriptor;",
    "openFileDescriptor($URI$STRING$CANCEL)Landroid/os/ParcelFileDescriptor;",
    "openAssetFileDescriptor($URI$STRING)Landroid/content/res/AssetFileDescriptor;",
    "openAssetFileDescriptor($URI$STRING$CANCEL)Landroid/content/res/AssetFileDescriptor;",
    "openTypedAssetFileDescriptor($URI$STRING$BUNDLE)Landroid/content/res/AssetFileDescriptor;",
)

/**
 * On an OPPO, OnePlus or realme phone (ColorOS, OxygenOS, realme UI) the ColorOS apps an app talks to are installed but
 * refuse one OPPO didn't sign: OnePlus My Files' bind of the storage-cleanup scan in com.coloros.phonemanager threw a
 * SecurityException and the app died at start (issue #113). Elsewhere they are missing, which the apps handle. Every
 * service call (bindService, startService, startForegroundService, stopService on any Context) and provider call
 * (query, call, insert, update, delete, open*, acquire*ContentProviderClient) goes through [runtime]
 * (runtime/ColorOS.java), which answers a refusal from another app's service, or from a provider under OPPO's names, as
 * Android answers for a missing one. Returns the service calls and provider calls moved.
 */
internal fun BytecodePatchContext.treatRefusedComponentsAsMissing(runtime: String): Pair<Int, Int> {
    val services = redirectContextCalls(runtime, SERVICE_CALLS.associateWith { it.replaceFirst("(", "(Landroid/content/Context;") })
    val providers = redirectCalls(runtime, mapOf("Landroid/content/ContentResolver;" to PROVIDER_CALLS))
    if (services == 0) throw PatchException("no bindService / startService calls found")
    if (providers == 0) throw PatchException("no ContentResolver calls found")
    return services to providers
}
