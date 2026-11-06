package org.ungoogled.patches.samsunggoodlock.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Merges Good Lock's module list with the Galaxy Store's entries into the list it shows. */
internal object PluginsMergeFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    filters = listOf(string("Plugin filtered: "), string(", not found in store")),
)

/** Downloads and installs a module through the Galaxy Store (a background job), the suspending variant. */
internal object InstallFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(string("installer"), string("package_name:")),
)

/** The same job started without waiting for it. */
internal object InstallNowFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(string("installer"), string("package_name:"), string("input_data:")),
)

/** Opens a module's page in the Galaxy Store app. */
internal object StorePageFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("samsungapps://ProductDetail/"), string("/?STUB=true")),
)


/** Reads the cached module list and Galaxy Store entries and merges them (see PluginsMergeFingerprint). */
internal object PluginsLoadFingerprint : Fingerprint(
    filters = listOf(string("storePlugins is null in local")),
)

/** The constructor of Good Lock's HTTP client engine (Ktor on OkHttp), which every request of the app goes through. */
internal object HttpEngineInitFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("createOkHttpClient")),
)
