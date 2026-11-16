package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

/**
 * Lets an app built for a newer Android install on Android [level] (13 = API 33): lowers the manifest's
 * minSdkVersion. Pair it with [redirectNewerAndroidMembers], which answers the newer Android calls the app makes
 * without checking the version.
 */
internal fun lowerMinSdkPatch(level: Int = 33) = resourcePatch(
    description = "Lets the app install on Android ${level - 20}.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val sdk = manifest.getElementsByTagName("uses-sdk").item(0) as? Element
                ?: throw PatchException("no <uses-sdk> in the manifest")
            val min = sdk.getAttribute("android:minSdkVersion").toIntOrNull()
                ?: throw PatchException("no minSdkVersion in the manifest")
            if (min > level) sdk.setAttribute("android:minSdkVersion", "$level")
        }
    }
}

/**
 * Lets an app built for Android 14 or newer run on Android 13: [lowerMinSdkPatch] plus [redirectNewerAndroidMembers]
 * through the extension's Android13 class ([helper], also the class the shims go into). [app] names the log.
 */
internal fun android13Patch(extension: String, helper: String, app: String) = bytecodePatch(
    description = "Lets the app run on Android 13.",
) {
    extendWith(extension)
    dependsOn(lowerMinSdkPatch(33))

    execute {
        val (calls, fields) = redirectNewerAndroidMembers(helper, helper)
        val records = replaceRecordSuperclass()
        // Android 13 fails on a blur of radius 0, which Samsung's animations start with (Samsung capture's countdown).
        val blurs = redirectCalls(
            listOf(
                "(FFLandroid/graphics/Shader\$TileMode;)",
                "(FFLandroid/graphics/RenderEffect;Landroid/graphics/Shader\$TileMode;)",
            ).associate { params ->
                "Landroid/graphics/RenderEffect;->createBlurEffect${params}Landroid/graphics/RenderEffect;" to
                    "$helper->createBlurEffect${params}Landroid/graphics/RenderEffect;"
            },
        )
        java.util.logging.Logger.getLogger(app)
            .info("Android 14+ members: $calls calls, $fields fields; records: $records; blur effects: $blurs")
        if (calls == 0) throw PatchException("no Android 14+ calls found")
    }
}

/**
 * Java records extend java.lang.Record, which Android has from 14 on; an app built for 14 keeps it (R8 already wrote
 * their equals, hashCode and toString), so on Android 13 the class fails to load. Here they extend Object instead.
 * Returns how many classes changed.
 */
internal fun BytecodePatchContext.replaceRecordSuperclass(): Int {
    val records = mutableListOf<String>()
    classDefForEach { c -> if (c.superclass == "Ljava/lang/Record;") records += c.type }
    for (type in records) {
        val mutable = mutableClassDefBy(type)
        mutable.setSuperClass("Ljava/lang/Object;")
        for (method in mutable.methods.filter { it.name == "<init>" }) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (insn.opcode != Opcode.INVOKE_DIRECT || ref.definingClass != "Ljava/lang/Record;" || ref.name != "<init>") continue
                val register = (insn as FiveRegisterInstruction).registerC
                method.replaceInstruction(index, "invoke-direct { v$register }, Ljava/lang/Object;-><init>()V")
            }
        }
    }
    return records.size
}

private val INVOKES = mapOf(
    Opcode.INVOKE_VIRTUAL to "invoke-virtual", Opcode.INVOKE_VIRTUAL_RANGE to "invoke-virtual",
    Opcode.INVOKE_INTERFACE to "invoke-interface", Opcode.INVOKE_INTERFACE_RANGE to "invoke-interface",
    Opcode.INVOKE_STATIC to "invoke-static", Opcode.INVOKE_STATIC_RANGE to "invoke-static",
)
private val SGET = setOf(Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT)
private val IGET = setOf(Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT)

private fun wide(type: String) = type == "J" || type == "D"
private fun isObject(type: String) = type.startsWith("L") || type.startsWith("[")
private fun registers(types: List<String>) = types.sumOf { if (wide(it)) 2 else 1 }

private fun moveResult(type: String) = when {
    wide(type) -> "move-result-wide"
    isObject(type) -> "move-result-object"
    else -> "move-result"
}

private fun returnOp(type: String) = when {
    type == "V" -> "return-void"
    wide(type) -> "return-wide v0"
    isObject(type) -> "return-object v0"
    else -> "return v0"
}

/**
 * The helper's Object answer in v0 as the shim's own return. An object answer is only cast when it is not null: the
 * cast names the type, and a type newer than the phone throws NoClassDefFoundError even for null.
 */
private fun olderReturn(type: String): String = when {
    type == "V" -> "return-void"
    isObject(type) -> """
        if-eqz v0, :none
        check-cast v0, $type
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """
    else -> returnFrom(type)
}

/**
 * Sends the app's calls to Android methods (and reads of Android fields) newer than the phone through a generated
 * shim in [shimClass]: from the member's own Android version on, the shim makes the original call; below it, it asks
 * [helper]'s older(receiver, key, args) / olderField(receiver, key), which answers like the newer member would where
 * it can and "not there" (nothing, 0, false, an empty list, null, a builder's own object) elsewhere. Apps built for Android 14 and
 * 15 call these without checking the version (their minSdk tells R8 to drop the checks), so on Android 13 each one is
 * a NoSuchMethodError the moment it runs. [members] is "Lowner;->name(params)return" / "Lowner;->name:type" (owner =
 * the first class at the call site the app doesn't define) to the API level it came with. Super calls and
 * constructors are left alone. Returns the calls and field reads moved.
 */
internal fun BytecodePatchContext.redirectNewerAndroidMembers(
    helper: String,
    shimClass: String,
    members: Map<String, Int> = NEWER_ANDROID_MEMBERS,
): Pair<Int, Int> {
    val shimDef = mutableClassDefBy(shimClass)
    val shims = mutableMapOf<String, String>()
    val appClasses = mutableMapOf<String, Pair<String?, Set<String>>?>()

    /** The app class's superclass and its declared member keys ("name(params)return", "name:type"); null for Android's. */
    fun appClass(type: String) = appClasses.getOrPut(type) {
        classDefByOrNull(type)?.let { c ->
            c.superclass to (c.methods.map { m -> "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}" } +
                c.fields.map { f -> "${f.name}:${f.type}" }).toSet()
        }
    }

    /** "Lowner;->member" for the first class up from [owner] the app doesn't define, or null when the app declares it. */
    fun keyOf(owner: String, member: String): String? {
        var type: String? = owner
        while (type != null) {
            val (superclass, declared) = appClass(type) ?: return "$type->$member"
            if (member in declared) return null
            type = superclass
        }
        return null
    }

    fun shim(id: String, parameters: List<String>, returnType: String, body: String): String = shims.getOrPut(id) {
        val name = "n${shims.size}"
        shimDef.methods.add(
            ImmutableMethod(
                shimClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                MutableMethodImplementation(5 + registers(parameters)),
            ).toMutable().apply { addInstructions(0, body) },
        )
        "$shimClass->$name(${parameters.joinToString("")})$returnType"
    }

    /** Below API [since] v0..v4 are free; the parameters follow them. */
    fun versionCheck(since: Int) = """
        sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
        const/16 v1, $since
        if-lt v0, v1, :older
    """

    fun methodShim(r: MethodReference, invoke: String, key: String, since: Int): String {
        val receiver = invoke != "invoke-static"
        val parameters = (if (receiver) listOf(r.definingClass) else emptyList()) + r.parameterTypes.map { it.toString() }
        val count = registers(parameters)
        val original = "${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}"
        val call = if (count == 0) "$invoke { }, $original" else "$invoke/range { p0 .. p${count - 1} }, $original"
        val result = if (r.returnType == "V") "" else "${moveResult(r.returnType)} v0"
        return shim(
            "$invoke $original", parameters, r.returnType,
            """
                ${versionCheck(since)}
                $call
                $result
                ${returnOp(r.returnType)}
                :older
                const-string v1, "$key"
                ${packArguments(parameters, if (receiver) 1 else 0)}
                ${if (receiver) "move-object/from16 v0, p0" else "const/4 v0, 0x0"}
                invoke-static { v0, v1, v2 }, $helper->older(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                ${olderReturn(r.returnType)}
            """,
        )
    }

    fun getterShim(f: FieldReference, receiver: Boolean, key: String, since: Int): String {
        val original = "${f.definingClass}->${f.name}:${f.type}"
        val op = if (receiver) "iget" else "sget"
        val suffix = when {
            wide(f.type) -> "-wide"
            isObject(f.type) -> "-object"
            f.type == "Z" -> "-boolean"
            f.type == "B" -> "-byte"
            f.type == "C" -> "-char"
            f.type == "S" -> "-short"
            else -> ""
        }
        return shim(
            "$op $original", if (receiver) listOf(f.definingClass) else emptyList(), f.type,
            """
                ${versionCheck(since)}
                ${if (receiver) "$op$suffix v0, p0, $original" else "$op$suffix v0, $original"}
                ${returnOp(f.type)}
                :older
                const-string v1, "$key"
                ${if (receiver) "move-object/from16 v0, p0" else "const/4 v0, 0x0"}
                invoke-static { v0, v1 }, $helper->olderField(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
                move-result-object v0
                ${olderReturn(f.type)}
            """,
        )
    }

    fun methodKey(r: MethodReference) =
        keyOf(r.definingClass, "${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}")?.takeIf { it in members }
    fun fieldKey(r: FieldReference) = keyOf(r.definingClass, "${r.name}:${r.type}")?.takeIf { it in members }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            val hit = m.implementation?.instructions?.any { insn ->
                when (val ref = (insn as? ReferenceInstruction)?.reference) {
                    is MethodReference -> insn.opcode in INVOKES && !ref.name.startsWith("<") && methodKey(ref) != null
                    is FieldReference -> (insn.opcode in SGET || insn.opcode in IGET) && fieldKey(ref) != null
                    else -> false
                }
            } ?: false
            if (hit) sites += c.type to m
        }
    }

    var calls = 0
    var fields = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        // Back to front: a field read becomes two instructions, which moves everything after it.
        for (index in instructions.indices.reversed()) {
            val insn = instructions[index]
            when (val ref = (insn as? ReferenceInstruction)?.reference) {
                is MethodReference -> {
                    val invoke = INVOKES[insn.opcode] ?: continue
                    if (ref.name.startsWith("<")) continue
                    val key = methodKey(ref) ?: continue
                    val target = methodShim(ref, invoke, key, members.getValue(key))
                    val call = when (insn) {
                        is FiveRegisterInstruction ->
                            listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                                .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $target") { "v$it" }
                        is RegisterRangeInstruction ->
                            "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $target"
                        else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
                    }
                    mutable.replaceInstruction(index, call)
                    calls++
                }
                is FieldReference -> {
                    val key = fieldKey(ref) ?: continue
                    when (insn.opcode) {
                        in SGET -> {
                            val a = (insn as OneRegisterInstruction).registerA
                            mutable.replaceInstruction(index, "invoke-static { }, ${getterShim(ref, false, key, members.getValue(key))}")
                            mutable.addInstruction(index + 1, "${moveResult(ref.type)} v$a")
                        }
                        in IGET -> {
                            val i = insn as TwoRegisterInstruction
                            mutable.replaceInstruction(
                                index,
                                "invoke-static/range { v${i.registerB} .. v${i.registerB} }, ${getterShim(ref, true, key, members.getValue(key))}",
                            )
                            mutable.addInstruction(index + 1, "${moveResult(ref.type)} v${i.registerA}")
                        }
                        else -> continue
                    }
                    fields++
                }
                else -> {}
            }
        }
    }
    return calls to fields
}
