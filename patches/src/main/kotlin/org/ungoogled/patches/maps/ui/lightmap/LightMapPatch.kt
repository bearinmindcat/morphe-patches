package org.ungoogled.patches.maps.ui.lightmap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * The map types' enum (ROADMAP, NAVIGATION, ... each with a day and a night variant),
 * found by its static initialiser's names.
 */
private object MapTypeEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(string("NAVIGATION_WALKING_LOW_LIGHT"), string("TERRAIN_VECTOR_CLIENT_LEGEND")),
)

/**
 * The map's listener for Maps' dark mode (the theme), which switches the map between its
 * day and night variants and the terrain layer between light and dark. The same listener
 * class also hands navigation's night mode to the map.
 */
private object MapDarkModeListenerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("PhoenixGoogleMap.setDarkModeEnabled")),
)

/** Navigation's night-mode controller (Automatic | Day | Night), found by its dump. */
private object NavigationNightModeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("GmmNavigationNightModeController"), string(" isNightTime: ")),
)

private const val INVALID_CALL = "Invalid call, onCreate needs to be called before using this method!"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * A small conditional's opcodes without its gotos and returns, which R8 lays out either way
 * (`cond ? a : b` as two returns in 26.36, as a goto to one return in 26.44); null unless it ends in a return.
 */
private fun Iterable<Instruction>.branchShape(): List<Opcode>? {
    val opcodes = map { it.opcode }
    if (opcodes.lastOrNull() != Opcode.RETURN_OBJECT) return null
    return opcodes.filter { it != Opcode.RETURN_OBJECT && it != Opcode.GOTO && it != Opcode.GOTO_16 && it != Opcode.GOTO_32 }
}

/** Index of `move-result vX` right after a `Boolean.booleanValue()` call. */
private fun MutableMethod.isBooleanValueResult(index: Int): Boolean {
    val call = implementation!!.instructions.getOrNull(index - 1)?.methodRef() ?: return false
    return call.definingClass == "Ljava/lang/Boolean;" && call.name == "booleanValue" &&
        getInstruction(index).opcode == Opcode.MOVE_RESULT
}

@Suppress("unused")
val lightMapPatch = bytecodePatch(
    name = "Light map",
    description = "Adds a Customization switch that keeps the map light while Maps' menus are dark.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // LIGHT_MAP is read at process start and at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, applicationStartHookPatch)

    execute {
        markPatched("lightMapPatched")

        // 1. Every map type's day/night pick, `night ? nightVariant : dayVariant`.
        val mapTypes = MapTypeEnumFingerprint.classDef
        val dayNight = mapTypes.methods.filter { m ->
            m.parameterTypes.map { it.toString() } == listOf("Z") && m.returnType.startsWith("L") &&
                m.implementation?.instructions?.branchShape() == listOf(Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT)
        }.singleOrNull() ?: throw PatchException("the map types' day/night pick not found in ${mapTypes.type}")
        dayNight.addInstructions(
            0,
            """
                invoke-static { p1 }, $SHAPES->mapNight(Z)Z
                move-result p1
            """,
        )

        // 2. The dark-mode listener: the boolean it read goes through mapNight() before the map
        //    and the terrain layer see it. It is the booleanValue() right before the map
        //    controller's "is the newer renderer drawing" check that precedes the trace label.
        val listener = MapDarkModeListenerFingerprint.method
        val label = MapDarkModeListenerFingerprint.instructionMatches.first().index
        val darkResult = (label downTo 1).firstOrNull { listener.isBooleanValueResult(it) }
            ?: throw PatchException("the dark-mode listener's booleanValue() not found before its trace label")
        val mapController = listener.getInstruction(darkResult + 1).methodRef()
            ?.takeIf { it.returnType == "Z" && it.parameterTypes.isEmpty() }?.definingClass
            ?: throw PatchException("the dark-mode listener no longer asks the map controller right after reading the mode")
        val dark = (listener.getInstruction(darkResult) as OneRegisterInstruction).registerA
        listener.addInstructions(
            darkResult + 1,
            """
                invoke-static { v$dark }, $SHAPES->mapNight(Z)Z
                move-result v$dark
            """,
        )

        // 3. Navigation's night mode reaches the map through the map controller's (Z)V setter
        //    that the same listener class calls with a booleanValue() it just read.
        val navSetterName = listener.implementation!!.instructions.withIndex().firstNotNullOfOrNull { (i, insn) ->
            insn.methodRef()?.takeIf {
                insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == mapController &&
                    it.returnType == "V" && it.parameterTypes.map { p -> p.toString() } == listOf("Z") &&
                    listener.isBooleanValueResult(i - 1)
            }?.name
        } ?: throw PatchException("navigation's night-mode setter on $mapController not found")
        val navSetter = mutableClassDefBy(mapController).methods.singleOrNull {
            it.name == navSetterName && it.parameterTypes.map { p -> p.toString() } == listOf("Z") && it.returnType == "V"
        } ?: throw PatchException("$mapController->$navSetterName(Z)V not found")
        if (navSetter.implementation!!.registerCount < 3) throw PatchException("$mapController->$navSetterName has no spare register")
        navSetter.addInstructions(
            0,
            """
                const-string v0, "$navSetterName"
                invoke-static { p0, p1, v0 }, $SHAPES->navMapNight(Ljava/lang/Object;ZLjava/lang/String;)Z
                move-result p1
            """,
        )

        // 5. The terrain layer (issue #164: Terrain stayed dark). Its dark tiles are picked by the layer kinds' own
        //    `night && this == TERRAIN ? TERRAIN_DARK : tiles`, called with a night flag the renderers keep for it:
        //    a boolean field, or an AtomicBoolean. The theme listener updates them (through mapNight() above), but
        //    only after its "map style already right?" check, which with Light map is always yes -- so they kept the
        //    value they were made with, the dark theme's. Every value written into them goes through mapNight().
        run<Unit> {
            val picks = mutableListOf<MethodReference>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    if (m.parameterTypes.map { it.toString() } != listOf("Z") || !m.returnType.startsWith("L")) continue
                    val code = m.implementation?.instructions?.toList() ?: continue
                    if (code.branchShape() != listOf(Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.SGET_OBJECT, Opcode.IGET_OBJECT) ||
                        code[1].fieldRef()?.definingClass != c.type || code[3].fieldRef()?.type != m.returnType
                    ) continue
                    picks += com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference(c.type, m.name, m.parameterTypes, m.returnType)
                }
            }
            val pick = picks.singleOrNull() ?: throw PatchException("the terrain layer's day/night pick: found ${picks.size}")
            val flags = mutableSetOf<FieldReference>()
            val atomics = mutableSetOf<FieldReference>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    val code = m.implementation?.instructions?.toList() ?: continue
                    code.forEachIndexed { i, insn ->
                        val r = insn.methodRef() ?: return@forEachIndexed
                        if (r.definingClass != pick.definingClass || r.name != pick.name || r.parameterTypes.map { it.toString() } != listOf("Z")) return@forEachIndexed
                        val night = (insn as FiveRegisterInstruction).registerD
                        // what wrote the flag shortly before: a field of this class, or AtomicBoolean.get()
                        for (j in i - 1 downTo maxOf(0, i - 20)) {
                            val w = code[j]
                            if (w.opcode == Opcode.IGET_BOOLEAN && (w as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA == night &&
                                w.fieldRef()?.definingClass == c.type
                            ) flags += w.fieldRef()!!
                            if (w.opcode == Opcode.MOVE_RESULT && (w as OneRegisterInstruction).registerA == night) {
                                val get = code.getOrNull(j - 1)?.methodRef()
                                if (get?.definingClass == "Ljava/util/concurrent/atomic/AtomicBoolean;" && get.name == "get") {
                                    val holder = (code[j - 1] as FiveRegisterInstruction).registerC
                                    (j - 2 downTo maxOf(0, j - 6)).firstNotNullOfOrNull { k ->
                                        code[k].fieldRef()?.takeIf {
                                            code[k].opcode == Opcode.IGET_OBJECT && (code[k] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA == holder
                                        }
                                    }?.let { atomics += it }
                                }
                            }
                        }
                    }
                }
            }
            if (flags.size != 1 || atomics.size != 1) throw PatchException("terrain night flags: ${flags.size} fields, ${atomics.size} atomics")
            val flag = flags.single()
            val atomic = atomics.single()
            var writes = 0
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                val hits = c.methods.filter { m ->
                    m.implementation?.instructions?.any { insn ->
                        (insn.opcode == Opcode.IPUT_BOOLEAN && insn.fieldRef()?.let { it.definingClass == flag.definingClass && it.name == flag.name } == true) ||
                            (c.type == atomic.definingClass && m.name == "<init>" && insn.methodRef()?.let {
                                it.definingClass == "Ljava/util/concurrent/atomic/AtomicBoolean;" && it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf("Z")
                            } == true)
                    } == true
                }
                if (hits.isEmpty()) return@classDefForEach
                val mutable = mutableClassDefBy(c.type)
                for (hit in hits) {
                    val m = mutable.methods.single { it.name == hit.name && it.parameterTypes == hit.parameterTypes && it.returnType == hit.returnType }
                    // from the last up, so earlier indices stay put
                    val sites = m.implementation!!.instructions.withIndex().filter { (k, insn) ->
                        (insn.opcode == Opcode.IPUT_BOOLEAN && insn.fieldRef()?.let { it.definingClass == flag.definingClass && it.name == flag.name } == true) ||
                            (m.name == "<init>" && c.type == atomic.definingClass && insn.methodRef()?.let {
                                it.definingClass == "Ljava/util/concurrent/atomic/AtomicBoolean;" && it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf("Z")
                            } == true && m.implementation!!.instructions.drop(k + 1).take(3).any { n ->
                                n.opcode == Opcode.IPUT_OBJECT && n.fieldRef()?.let { f -> f.definingClass == atomic.definingClass && f.name == atomic.name } == true
                            })
                    }.map { it.index }.reversed()
                    for (k in sites) {
                        val insn = m.implementation!!.instructions[k]
                        if (m.implementation!!.instructions[k].location.labels.isNotEmpty()) throw PatchException("terrain night flag write in ${c.type}->${m.name} is a branch target")
                        val value = if (insn.opcode == Opcode.IPUT_BOOLEAN) (insn as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
                            else (insn as FiveRegisterInstruction).registerD
                        m.addInstructions(
                            k,
                            """
                                invoke-static/range { v$value .. v$value }, $SHAPES->mapNight(Z)Z
                                move-result v$value
                            """,
                        )
                        writes++
                    }
                }
            }
            if (writes < 2) throw PatchException("terrain night flags: only $writes writes found")
        }

        // 4. Navigation's night-mode controller. useNightMode() is `allowed && useNightModeIgnoringAllowedState()`,
        //    and the latter is `forced == DAY ? false : forced == NIGHT || nightTime || tunnel || car`;
        //    their fields go to the extension, which works the map's state out for the scheme
        //    Black theme captured. The update method is `nightMode.set(useNightMode())`.
        val controller = NavigationNightModeFingerprint.classDef
        fun MutableMethod.hasInvalidCall() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == INVALID_CALL
        } == true
        val useNight = controller.methods.filter { m ->
            m.returnType == "Z" && m.parameterTypes.isEmpty() && m.hasInvalidCall() &&
                m.implementation!!.instructions.any { it.methodRef()?.let { r -> r.definingClass == controller.type && r.returnType == "Z" } == true }
        }.singleOrNull() ?: throw PatchException("navigation's useNightMode() not found in ${controller.type}")
        val ignoringAllowed = useNight.implementation!!.instructions.firstNotNullOf {
            it.methodRef()?.takeIf { r -> r.definingClass == controller.type && r.returnType == "Z" }
        }.name
        val created = useNight.implementation!!.instructions.firstOrNull { it.opcode == Opcode.IGET }?.fieldRef()?.name
            ?: throw PatchException("navigation's useNightMode() no longer checks its created count first")
        val allowed = useNight.implementation!!.instructions.filter { it.opcode == Opcode.IGET_BOOLEAN }
            .mapNotNull { it.fieldRef()?.takeIf { f -> f.definingClass == controller.type }?.name }
            .singleOrNull() ?: throw PatchException("navigation's useNightMode() no longer reads one allowed flag")
        val auto = controller.methods.singleOrNull { it.name == ignoringAllowed && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            ?.implementation?.instructions?.let { insns ->
                val ordinal = insns.indexOfFirst { it.methodRef()?.name == "ordinal" }
                if (ordinal < 0) null else insns.drop(ordinal).filter { it.opcode == Opcode.IGET_BOOLEAN }
                    .mapNotNull { it.fieldRef()?.takeIf { f -> f.definingClass == controller.type }?.name }
            }
        if (auto == null || auto.size != 3) throw PatchException("navigation's night-mode inputs not found in $ignoringAllowed() (${auto?.size})")
        val update = controller.methods.filter { m ->
            val insns = m.implementation?.instructions?.toList() ?: return@filter false
            m.returnType == "V" && m.parameterTypes.isEmpty() &&
                insns.map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID) &&
                insns[1].methodRef()?.name == useNight.name
        }.singleOrNull() ?: throw PatchException("navigation's night-mode update not found in ${controller.type}")
        // Its first register is free until the field read that starts it.
        val free = (update.getInstruction(0) as OneRegisterInstruction).registerA
        val self = (update.getInstruction(1) as FiveRegisterInstruction).registerC
        if (free == self) throw PatchException("navigation's night-mode update has no spare register")
        update.addInstructions(
            0,
            """
                const-string v$free, "$created,$allowed,${auto.joinToString(",")}"
                invoke-static { v$self, v$free }, $SHAPES->navNightInputs(Ljava/lang/Object;Ljava/lang/String;)V
            """,
        )
    }
}
