package org.ungoogled.patches.coloroscamera.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload
import com.android.tools.smali.dexlib2.builder.instruction.BuilderSparseSwitchPayload
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.coloroscamera.ColorOsCameraConstants.COMPATIBILITY_COLOROS_CAMERA
import java.util.BitSet

private const val GALLERY = "Lorg/ungoogled/coloroscamera/Gallery;"

/** The camera's own "review this photo" action, which it sends to OPPO's gallery package only. */
private const val REVIEW = "com.android.camera.action.REVIEW"

/** The "media-from" extra on every hand-over to the gallery for viewing (not on the edit/doodle ones). */
private const val FROM_CAMERA = "from_camera"

private const val SET_DATA_AND_TYPE =
    "Landroid/content/Intent;->setDataAndType(Landroid/net/Uri;Ljava/lang/String;)Landroid/content/Intent;"

/**
 * GalleryUtil: the camera's "which gallery gets the photos" answer, OPPO's Photos package, or
 * "com.oplus.gallery.base" when Photos was removed through ColorOS's removable apps. A singleton with a static getter.
 */
private object GalleryPackageFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("com.oplus.gallery.base"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The camera hands every photo or video it opens for viewing to OPPO's gallery: an intent addressed to that one
 * package, with extras for its own thumbnail transition. That is the thumbnail next to the shutter, and the photos in
 * the camera's own preview strip; three places in each build (a "seamless" one that embeds the gallery in the
 * camera's window, and two plain ones, partly inlined). With OPPO's gallery disabled or removed they stop: nothing
 * opens, or an "Enable Photos?" dialog. Each of them now first asks the extension's Gallery.open(), which leaves
 * them alone while OPPO's gallery is installed and enabled, and otherwise opens the photo in the app the user picked
 * for viewing pictures (ACTION_VIEW with a read grant; on the lock screen, after the user unlocks).
 */
@Suppress("unused")
val colorOsCameraOpenInGalleryPatch = bytecodePatch(
    name = "Open photos in your gallery app",
    description = "Opens photos in your gallery app when OPPO's gallery is disabled or removed. Root (mount) install only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CAMERA)
    extendWith(COLOROS_CAMERA_EXTENSION)

    execute {
        // Gallery.oppoGallery() returns what the camera's GalleryUtil says.
        val util = GalleryPackageFingerprint.classDef.type
        val getter = GalleryPackageFingerprint.method
        val instance = GalleryPackageFingerprint.classDef.methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == util
        } ?: throw PatchException("$util: no static instance getter next to the gallery package")
        mutableClassDefBy(GALLERY).methods.singleOrNull {
            it.name == "oppoGallery" && it.parameterTypes.isEmpty()
        }?.addInstructions(
            0,
            """
                invoke-static {}, $util->${instance.name}()$util
                move-result-object v0
                invoke-virtual { v0 }, $util->${getter.name}()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        ) ?: throw PatchException("$GALLERY->oppoGallery() missing from the extension")

        // The hand-overs: methods sending the review action with the "from camera" extra.
        val launches = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val strings = method.implementation?.instructions?.mapNotNullTo(HashSet()) {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                } ?: continue
                if (REVIEW in strings && FROM_CAMERA in strings) launches += classDef.type to method
            }
        }
        // The seamless one gets the photo itself: (View, Uri, type String, Bitmap, ...).
        val seamless = launches.filter { (_, m) ->
            m.parameterTypes.map(CharSequence::toString).take(4) ==
                listOf("Landroid/view/View;", "Landroid/net/Uri;", "Ljava/lang/String;", "Landroid/graphics/Bitmap;")
        }
        if (seamless.size != 1 || launches.size < 2) {
            throw PatchException(
                "expected one seamless and at least one plain gallery hand-over, found ${seamless.size} of ${launches.size}",
            )
        }
        val seamlessRef = seamless.single().let { (type, m) -> "$type->${m.name}(${m.parameterTypes.joinToString("")})" }

        for ((type, original) in launches) {
            val method = mutableClassDefBy(type).methods.first {
                it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
            }
            hookHandOver(method, seamlessRef)
        }
    }
}

/**
 * Where [method] decides to hand a photo to the gallery, insert:
 *   if (Gallery.open(view or this, uri, type)) return;   (false for a method returning a boolean: "gallery not opened")
 * The point is the start of the method, or, in a method that also has the seamless branch, the test choosing between
 * the two (after the method's own "is the camera busy?" checks). The uri and type are read the way the method's
 * setDataAndType call gets them, from the method's parameters; the registers used are dead there.
 */
private fun hookHandOver(method: MutableMethod, seamlessRef: String) {
    val where = "${method.definingClass}->${method.name}"
    val instructions = method.implementation!!.instructions.toList()
    val flow = Flow(method)

    // Uri and type: traced back from the intent's setDataAndType to a parameter.
    val set = instructions.indexOfFirst { insn ->
        (insn as? ReferenceInstruction)?.reference?.let { it is MethodReference && it.toString() == SET_DATA_AND_TYPE } == true
    }
    if (set < 0) throw PatchException("$where: no setDataAndType")
    val (uriRegister, typeRegister) = when (val insn = instructions[set]) {
        is FiveRegisterInstruction -> insn.registerD to insn.registerE
        is RegisterRangeInstruction -> (insn.startRegister + 1) to (insn.startRegister + 2)
        else -> throw PatchException("$where: unexpected setDataAndType form")
    }
    val uri = flow.trace(uriRegister, set) ?: throw PatchException("$where: can't tell where the photo's uri comes from")
    val mime = flow.trace(typeRegister, set) ?: throw PatchException("$where: can't tell where the photo's type comes from")

    // The tapped view when there is one, else the camera object (the extension finds its activity).
    val parameters = method.parameterTypes.map(CharSequence::toString)
    val view = parameters.indexOf("Landroid/view/View;")
    val anchor = when {
        view >= 0 -> flow.parameterRegister(view)
        !AccessFlags.STATIC.isSet(method.accessFlags) -> flow.thisRegister()
        else -> throw PatchException("$where: nothing to find the camera's activity from")
    }

    // Where: the seamless/plain test when the method has both, else the start.
    val seamlessCall = instructions.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.let { ref -> ref is MethodReference && ref.toString().startsWith(seamlessRef) } == true
    }
    val index = if (seamlessCall < 0) {
        0
    } else {
        // The nearest test before the call that jumps past it: if-eqz/if-nez on a flag read just before.
        (seamlessCall - 1 downTo 0).firstOrNull { i ->
            val insn = instructions[i]
            (insn.opcode == Opcode.IF_EQZ || insn.opcode == Opcode.IF_NEZ) && flow.target(i) > seamlessCall
        }?.also { i ->
            val flag = (instructions[i] as OneRegisterInstruction).registerA
            val def = (i - 1 downTo 0).firstOrNull { flow.defs(it).get(flag) }
            if (def == null || instructions[def].opcode != Opcode.IGET_BOOLEAN) {
                throw PatchException("$where: the seamless test isn't on a flag")
            }
        } ?: throw PatchException("$where: no test choosing the seamless hand-over")
    }
    if (flow.isBranchTarget(index)) throw PatchException("$where: insertion point is a branch target")
    for (base in setOf(anchor, uri.base, mime.base)) {
        if (!flow.intactAt(base, index)) throw PatchException("$where: parameter v$base no longer holds its value at the hook")
    }

    // Three free registers below v16 (the invoke takes 4-bit registers).
    val live = flow.liveIn(index)
    val locals = method.implementation!!.registerCount - flow.parameterRegisters
    val free = (0 until minOf(16, locals)).filter { !live.get(it) }
    if (free.size < 3) throw PatchException("$where: no free registers at the hook")
    val (a, u, t) = free

    val returns = if (method.returnType == "V") "return-void" else "const/4 v$a, 0x0\nreturn v$a"
    if (method.returnType != "V" && method.returnType != "Z") throw PatchException("$where returns ${method.returnType}")
    method.addInstructionsWithLabels(
        index,
        """
            move-object/from16 v$a, v$anchor
            ${uri.load(u)}
            ${mime.load(t)}
            invoke-static { v$a, v$u, v$t }, $GALLERY->open(Ljava/lang/Object;Landroid/net/Uri;Ljava/lang/String;)Z
            move-result v$a
            if-eqz v$a, :stock
            $returns
        """,
        ExternalLabel("stock", method.implementation!!.instructions[index]),
    )
}

/** A value read from parameter register [base] through fields, no-argument getters and casts. */
private class Chain(val base: Int, val steps: List<Instruction>) {
    fun load(into: Int): String = buildString {
        appendLine("move-object/from16 v$into, v$base")
        for (step in steps) {
            when (step.opcode) {
                Opcode.IGET_OBJECT -> appendLine("iget-object v$into, v$into, ${(step as ReferenceInstruction).reference}")
                Opcode.CHECK_CAST -> appendLine("check-cast v$into, ${((step as ReferenceInstruction).reference as TypeReference).type}")
                in GETTER_CALLS -> {
                    val name = step.opcode.name.removeSuffix("_RANGE").lowercase().replace('_', '-')
                    appendLine("$name { v$into }, ${(step as ReferenceInstruction).reference}")
                    appendLine("move-result-object v$into")
                }
                else -> throw PatchException("unexpected step ${step.opcode}")
            }
        }
    }
}

/** [Flow.reachingWrites]: the value a register had when the method started. */
private const val ENTRY = -1

/** Calls a value can come from: a no-argument getter on an object. */
private val GETTER_CALLS = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_DIRECT,
    Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_DIRECT_RANGE,
)

/** Control flow, liveness and where a register's value comes from, for one method. */
private class Flow(method: MutableMethod) {
    private val impl = method.implementation!!
    private val insns = impl.instructions.toList()
    private val n = insns.size
    private val registers = impl.registerCount
    val parameterRegisters = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
        method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    private val firstParameter = registers - parameterRegisters
    private val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
    private val parameterOffsets = run {
        var r = firstParameter + (if (isStatic) 0 else 1)
        method.parameterTypes.map { p -> r.also { r += if (p.toString() == "J" || p.toString() == "D") 2 else 1 } }
    }

    fun thisRegister() = firstParameter
    fun parameterRegister(i: Int) = parameterOffsets[i]

    private val uses = Array(n) { BitSet() }
    private val defs = Array(n) { BitSet() }
    private val successors = Array(n) { mutableListOf<Int>() }
    private val handlers = Array(n) { mutableListOf<Int>() }
    private val targets = HashSet<Int>()
    private val isPayload = BooleanArray(n)

    fun defs(i: Int): BitSet = defs[i]
    fun isBranchTarget(i: Int) = i in targets

    fun target(i: Int): Int = (insns[i] as BuilderOffsetInstruction).target.location.index

    init {
        for (i in 0 until n) {
            val insn = insns[i]
            when (insn.opcode) {
                Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD -> {
                    isPayload[i] = true
                    continue
                }
                else -> Unit
            }
            registersOf(insn, uses[i], defs[i])
            if (insn.opcode.canContinue() && i + 1 < n) successors[i] += i + 1
            when (insn.opcode) {
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                    val payload = insns[target(i)]
                    val elements = when (payload) {
                        is BuilderPackedSwitchPayload -> payload.switchElements
                        is BuilderSparseSwitchPayload -> payload.switchElements
                        else -> throw PatchException("switch without a payload")
                    }
                    for (e in elements) {
                        val t = e.target.location.index
                        successors[i] += t
                        targets += t
                    }
                }
                Opcode.FILL_ARRAY_DATA -> Unit
                else -> if (insn is BuilderOffsetInstruction) {
                    successors[i] += target(i)
                    targets += target(i)
                }
            }
        }
        for (tryBlock in impl.tryBlocks) {
            val start = tryBlock.start.location.index
            val end = tryBlock.end.location.index
            val handler = tryBlock.exceptionHandler.handler.location.index
            targets += handler
            for (i in start until minOf(end, n)) if (insns[i].opcode.canThrow()) handlers[i] += handler
        }
    }

    private val liveIn: Array<BitSet> by lazy {
        val live = Array(n) { BitSet() }
        var changed = true
        while (changed) {
            changed = false
            for (i in n - 1 downTo 0) {
                if (isPayload[i]) continue
                val out = BitSet()
                for (s in successors[i]) out.or(live[s])
                out.andNot(defs[i])
                out.or(uses[i])
                // Thrown before its result is written: the handler sees the old registers.
                for (h in handlers[i]) out.or(live[h])
                if (out != live[i]) {
                    live[i] = out
                    changed = true
                }
            }
        }
        live
    }

    fun liveIn(i: Int): BitSet = liveIn[i]

    /** True when [register] can only hold its value from the method's entry at instruction [at]. */
    fun intactAt(register: Int, at: Int): Boolean = reachingWrites(register, at) == setOf(ENTRY)

    /**
     * The writes to [register] that can reach instruction [at] ([ENTRY] for the value it had when the method started).
     * A write by an instruction that throws doesn't happen on the way to its handler.
     */
    fun reachingWrites(register: Int, at: Int): Set<Int> {
        val reaching = arrayOfNulls<MutableSet<Int>>(n)
        reaching[0] = mutableSetOf(ENTRY)
        val work = ArrayDeque<Int>().apply { add(0) }
        fun flowTo(target: Int, writes: Set<Int>) {
            val current = reaching[target]
            if (current == null) {
                reaching[target] = writes.toMutableSet()
                work.add(target)
            } else if (current.addAll(writes)) {
                work.add(target)
            }
        }
        while (work.isNotEmpty()) {
            val i = work.removeFirst()
            if (isPayload[i]) continue
            val incoming = reaching[i]!!
            val outgoing = if (defs[i].get(register)) setOf(i) else incoming
            for (s in successors[i]) flowTo(s, outgoing)
            for (h in handlers[i]) flowTo(h, incoming)
        }
        return reaching[at] ?: emptySet()
    }

    /**
     * How [register] gets its value at [at]: back through the nearest writes to a parameter, following moves, field
     * reads, no-argument getters and casts. Null for anything else.
     */
    fun trace(register: Int, at: Int): Chain? {
        val steps = ArrayDeque<Instruction>()
        var r = register
        var i = at
        while (true) {
            // Exactly one write gets here: the parameter's own value, or one instruction.
            val def = reachingWrites(r, i).singleOrNull() ?: return null
            if (def == ENTRY) return if (r >= firstParameter) Chain(r, steps.toList()) else null
            val insn = insns[def]
            when (insn.opcode) {
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> r = (insn as TwoRegisterInstruction).registerB
                Opcode.IGET_OBJECT -> { steps.addFirst(insn); r = (insn as TwoRegisterInstruction).registerB }
                Opcode.CHECK_CAST -> steps.addFirst(insn)
                Opcode.MOVE_RESULT_OBJECT -> {
                    val call = insns.getOrNull(def - 1) ?: return null
                    if (call.opcode !in GETTER_CALLS) return null
                    val ref = (call as ReferenceInstruction).reference as MethodReference
                    if (ref.parameterTypes.isNotEmpty()) return null
                    steps.addFirst(call)
                    r = when (call) {
                        is FiveRegisterInstruction -> call.registerC.takeIf { call.registerCount == 1 }
                        is RegisterRangeInstruction -> call.startRegister.takeIf { call.registerCount == 1 }
                        else -> null
                    } ?: return null
                    i = def - 1
                    continue
                }
                else -> return null
            }
            i = def
        }
    }

    private fun registersOf(insn: Instruction, uses: BitSet, defs: BitSet) {
        val op = insn.opcode
        val wideOperands = op.name.contains("WIDE") || op.name.contains("LONG") || op.name.contains("DOUBLE")
        fun use(r: Int) { uses.set(r); if (wideOperands) uses.set(r + 1) }
        fun def(r: Int) { defs.set(r); if (op.setsWideRegister()) defs.set(r + 1) }
        when (insn) {
            is FiveRegisterInstruction -> {
                val regs = listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                regs.take(insn.registerCount).forEach { uses.set(it) }
            }
            is RegisterRangeInstruction -> (insn.startRegister until insn.startRegister + insn.registerCount).forEach { uses.set(it) }
            is OneRegisterInstruction -> {
                val a = insn.registerA
                val alsoReads = op == Opcode.CHECK_CAST || op.name.endsWith("_2ADDR")
                if (op.setsRegister()) { def(a); if (alsoReads) use(a) } else use(a)
                if (insn is TwoRegisterInstruction) use(insn.registerB)
                if (insn is ThreeRegisterInstruction) use(insn.registerC)
            }
            else -> Unit
        }
    }
}
