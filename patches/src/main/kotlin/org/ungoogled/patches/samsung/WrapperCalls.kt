package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Sends every call in the app to an instance method of one of [owners] for which the extension class [target] has a
 * static wrapper (same name, the receiver first, then the same parameters and return type) to that wrapper, through
 * redirectCalls. The wrapper set is read from [target] itself, so the extension decides what is wrapped. Returns the
 * calls moved.
 */
internal fun BytecodePatchContext.redirectToWrappers(target: String, owners: Set<String>): Int {
    val calls = mutableMapOf<String, MutableSet<String>>()
    for (m in mutableClassDefBy(target).methods) {
        if (!AccessFlags.STATIC.isSet(m.accessFlags) || !AccessFlags.PUBLIC.isSet(m.accessFlags)) continue
        val parameters = m.parameterTypes.map { it.toString() }
        val owner = parameters.firstOrNull() ?: continue
        if (owner !in owners) continue
        calls.getOrPut(owner) { mutableSetOf() } += "${m.name}(${parameters.drop(1).joinToString("")})${m.returnType}"
    }
    return redirectCalls(target, calls)
}
