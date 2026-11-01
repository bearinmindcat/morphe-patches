package org.ungoogled.patches.xiaomi

import app.morphe.patcher.patch.BytecodePatchContext
import org.ungoogled.patches.samsung.redirectCalls

private const val GET = "get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
private const val GET_ONE = "get(Ljava/lang/String;)Ljava/lang/String;"

/** Property reads: Android's own (hidden) class, and MiCloud's wrapper of it (a micloud-sdk class). */
private val PROPERTY_READS = mapOf(
    "Landroid/os/SystemProperties;" to setOf(
        GET, GET_ONE, "getInt(Ljava/lang/String;I)I", "getLong(Ljava/lang/String;J)J", "getBoolean(Ljava/lang/String;Z)Z",
    ),
    "Lmiui/cloud/os/SystemProperties;" to setOf(GET, GET_ONE),
)

/**
 * Sends an app's system property reads to its extension's MiuiProps class (generated from
 * .github/scripts/xiaomi-standins/runtime), which answers Xiaomi's own properties as a Global build does where the
 * phone has none (miuix's "international" check is ro.product.mod_device ending in "_global"; region properties
 * default to China). Returns the calls moved.
 */
internal fun BytecodePatchContext.redirectMiuiProperties(props: String): Int = redirectCalls(props, PROPERTY_READS)
