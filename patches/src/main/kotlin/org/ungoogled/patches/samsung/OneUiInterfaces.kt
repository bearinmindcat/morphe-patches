package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private val ONE_UI_METHOD = Regex("^sem[A-Z]")

private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"

/**
 * An app interface can declare one of One UI's methods (Video Player's player-screen interface has semIsResumed())
 * for its activities to inherit from One UI's Activity; elsewhere nothing implements it and the first call is an
 * AbstractMethodError. Gives each concrete app class that lacks such a method one that asks [oneUi]'s
 * callSuper(receiver, key, args) for the answer, keyed by the nearest Android superclass (as a super call would be).
 * Returns how many methods it added.
 */
internal fun BytecodePatchContext.implementOneUiInterfaceMethods(oneUi: String): Int {
    val classes = mutableMapOf<String, ClassDef>()
    classDefForEach { classes[it.type] = it }

    val declared = mutableMapOf<String, List<Method>>()
    for (c in classes.values) {
        if (!AccessFlags.INTERFACE.isSet(c.accessFlags) || c.type.startsWith("Lorg/ungoogled/")) continue
        val methods = c.methods.filter { ONE_UI_METHOD.containsMatchIn(it.name) && it.implementation == null }
        if (methods.isNotEmpty()) declared[c.type] = methods
    }
    if (declared.isEmpty()) return 0

    fun interfacesOf(type: String, into: MutableSet<String> = mutableSetOf()): Set<String> {
        val c = classes[type] ?: return into
        for (i in c.interfaces) if (into.add(i)) interfacesOf(i, into)
        c.superclass?.let { interfacesOf(it, into) }
        return into
    }

    var added = 0
    for (c in classes.values) {
        if (c.type.startsWith("Lorg/ungoogled/")) continue
        if (AccessFlags.INTERFACE.isSet(c.accessFlags) || AccessFlags.ABSTRACT.isSet(c.accessFlags)) continue
        val wanted = interfacesOf(c.type).flatMap { declared[it].orEmpty() }.distinctBy { it.signature() }
        if (wanted.isEmpty()) continue

        // What the class and its app superclasses already implement, and the Android class they end at.
        val implemented = mutableSetOf<String>()
        var type: String? = c.type
        while (type != null && classes.containsKey(type)) {
            classes[type]!!.methods.filter { it.implementation != null }.forEach { implemented += it.signature() }
            type = classes[type]!!.superclass
        }
        val owner = type ?: "Ljava/lang/Object;"

        for (m in wanted) {
            if (m.signature() in implemented) continue
            val parameters = listOf(c.type) + m.parameterTypes.map { it.toString() }
            val wide = parameters.count { it == "J" || it == "D" }
            val method = ImmutableMethod(
                c.type, m.name, m.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) }, m.returnType,
                AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(5 + parameters.size + wide),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-string v1, "$owner->${m.signature()}"
                        ${packArguments(parameters, 1)}
                        move-object/from16 v0, p0
                        invoke-static { v0, v1, v2 }, $oneUi->callSuper(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        ${returnFrom(m.returnType)}
                    """,
                )
            }
            mutableClassDefBy(c.type).methods.add(method)
            added++
        }
    }
    return added
}
