package org.ungoogled.patches.xiaomi

import app.morphe.patcher.patch.BytecodePatchContext
import org.ungoogled.patches.samsung.rewriteStringLiterals

private const val SYSTEM_PROPERTIES = "android.os.SystemProperties"

/**
 * Like [redirectMiuiProperties], for apps whose libraries read system properties by reflection
 * (Class.forName("android.os.SystemProperties").getMethod("get", ...)): the class name they look up becomes the
 * extension's MiuiProps ([props], "Lorg/ungoogled/<app>/MiuiProps;"), which has the same static get / getInt / getLong
 * / getBoolean, so their lookups find it and get the Global build's answers for Xiaomi's region properties. Lookups of
 * anything MiuiProps lacks (set, ...) fail as they would where the hidden class is blocked, which those callers handle.
 * Returns the literals rewritten.
 */
internal fun BytecodePatchContext.redirectReflectedMiuiProperties(props: String): Int {
    val name = props.removePrefix("L").removeSuffix(";").replace('/', '.')
    return rewriteStringLiterals { if (it == SYSTEM_PROPERTIES) name else null }
}
