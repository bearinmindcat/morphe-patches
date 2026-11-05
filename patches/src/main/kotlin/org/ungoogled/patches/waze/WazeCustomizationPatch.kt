package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** org.ungoogled.waze.Customization, the runtime half (extensions/waze). */
internal const val WAZE_CUSTOMIZATION = "Lorg/ungoogled/waze/Customization;"
private const val OPEN = "Lorg/ungoogled/waze/Customization\$Open;"

/** Waze's string table class; its MAIN_MENU_SHUT_DOWN_ANDROID field holds the "Shut off" label's resource id. */
private const val WAZE_STRINGS = "Lcom/waze/R\$string;"
private const val SHUT_OFF_NAME = "MAIN_MENU_SHUT_DOWN_ANDROID"

/** Waze's Application class (the manifest's android:name), for a Context and the open screen. */
internal object WazeApplicationCreateFingerprint : Fingerprint(
    definingClass = "Lcom/waze/mobile/WazeMobileApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * A "Customization" row at the bottom of Waze's side menu, under Shut off, opening a screen with a
 * switch for each patched feature that can be turned on and off in the app. The screen is a
 * full-screen panel of the extension's, so no Activity is added to the manifest.
 */
internal val wazeCustomizationPatch = bytecodePatch(
    description = "Adds the Customization row to Waze's side menu.",
) {
    extendWith("extensions/waze.mpe")

    execute {
        WazeApplicationCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $WAZE_CUSTOMIZATION->init(Landroid/app/Application;)V",
        )

        val customization = mutableClassDefBy(WAZE_CUSTOMIZATION)

        // uaDeclared(): one feature(key) call per applied feature, added by declareWazeToggle.
        customization.methods.add(
            ImmutableMethod(
                WAZE_CUSTOMIZATION, "uaDeclared", emptyList(), "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
            ).toMutable().apply { addInstructions(0, "return-void") },
        )

        // The side menu's rows, found from the Shut off row: the label's resource id, the method that loads it,
        // and the row composable it is handed to. Waze renames all of these from one build to the next.
        val shutOff = shutOffLabel()
        val menu = mutableClassDefByOrNull { c ->
            c.type.startsWith("Lcom/waze/") && c.methods.any { m -> m.loadsLiteral(shutOff) }
        } ?: throw PatchException("side menu with the Shut off row not found")
        val method = menu.methods.singleOrNull { it.loadsLiteral(shutOff) }
            ?: throw PatchException("side menu with the Shut off row not found")
        val ins = method.implementation!!.instructions.toList()
        val label = ins.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == shutOff }
        // The row composable: label, modifier, two flags, a long, icon, trailing, onClick, composer, changed, defaults.
        val call = (label + 1 until ins.size).firstOrNull {
            val ref = (ins[it] as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstOrNull false
            ins[it].opcode == Opcode.INVOKE_STATIC_RANGE && ref.returnType == "V" && ref.parameterTypes.size == 11 &&
                ref.parameterTypes[0].toString() == "Ljava/lang/String;" && ref.parameterTypes[8].toString() == COMPOSER
        } ?: throw PatchException("Shut off row call not found")
        val row = ins[call] as RegisterRangeInstruction
        val rowRef = (ins[call] as ReferenceInstruction).reference as MethodReference
        val types = rowRef.parameterTypes.map { it.toString() }
        if (row.registerCount != 12 || types[1] != "Landroidx/compose/ui/Modifier;" || types[4] != "J" || types[9] != "I" || types[10] != "I") {
            throw PatchException("Shut off row call changed shape: $rowRef")
        }
        val iconType = types[5]
        val function0 = types[7]
        // The row's icon, as the Shut off row builds it: an image made from a drawable id, wrapped in the icon class
        // whose constructor takes the image, optional parts left null, and a mask of those left to their defaults.
        val iconInit = (label until call).firstNotNullOfOrNull { i ->
            val ref = (ins[i] as? ReferenceInstruction)?.reference as? MethodReference
            if (ins[i].opcode == Opcode.INVOKE_DIRECT && ref?.name == "<init>" && ref.definingClass == iconType) i to ref else null
        } ?: throw PatchException("Shut off row icon not found")
        val iconParams = iconInit.second.parameterTypes.map { it.toString() }
        if (iconParams.size !in 2..4 || iconParams.last() != "I" || iconParams.dropLast(1).any { !it.startsWith("L") }) {
            throw PatchException("Shut off row icon changed shape: ${iconInit.second}")
        }
        val imageInit = (label until iconInit.first).firstNotNullOfOrNull { i ->
            val ref = (ins[i] as? ReferenceInstruction)?.reference as? MethodReference
            if (ins[i].opcode == Opcode.INVOKE_DIRECT && ref?.name == "<init>" && ref.parameterTypes.map { it.toString() } == listOf("I")) ref else null
        } ?: throw PatchException("Shut off row image not found")
        val iconCall = ins[iconInit.first] as FiveRegisterInstruction
        val maskRegister = listOf(iconCall.registerC, iconCall.registerD, iconCall.registerE, iconCall.registerF, iconCall.registerG)[iconCall.registerCount - 1]
        val mask = (iconInit.first - 1 downTo 0).firstNotNullOfOrNull { i ->
            val insn = ins[i]
            if (insn !is OneRegisterInstruction || insn.registerA != maskRegister || !insn.opcode.setsRegister()) return@firstNotNullOfOrNull null
            (insn as? NarrowLiteralInstruction)?.narrowLiteral ?: -1
        }?.takeIf { it >= 0 } ?: (1 until iconParams.size - 1).sumOf { 1 shl it }

        // The row's click handler is the extension's Open, made Waze's Function0 here (its invoke() is there).
        val open = mutableClassDefBy(OPEN)
        if (open.methods.none { it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;" }) {
            throw PatchException("$OPEN.invoke() not found")
        }
        if (classDefBy(function0).methods.singleOrNull()?.let { it.name == "invoke" && it.parameterTypes.isEmpty() } != true) {
            throw PatchException("$function0 is not Function0")
        }
        if (function0 !in open.interfaces) open.interfaces.add(function0)

        // Customization.menuRow(composer): the row itself, drawn by Waze's own row composable, with the
        // same defaults mask as the Shut off row (0x50: the long and the trailing slot left default).
        val nulls = iconParams.size - 2
        val iconArgs = (listOf("v7") + List(nulls) { "v8" } + "v9").joinToString(", ")
        customization.methods.add(
            ImmutableMethod(
                WAZE_CUSTOMIZATION, "menuRow",
                listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(13),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p0, $COMPOSER
                        const-string v0, "Customization"
                        sget-object v1, Landroidx/compose/ui/Modifier;->Companion:Landroidx/compose/ui/Modifier${'$'}Companion;
                        check-cast v1, Landroidx/compose/ui/Modifier;
                        const/4 v2, 0x0
                        const/4 v3, 0x0
                        const-wide/16 v4, 0x0
                        new-instance v6, $iconType
                        new-instance v7, ${imageInit.definingClass}
                        invoke-static { }, $WAZE_CUSTOMIZATION->iconRes()I
                        move-result v8
                        invoke-direct { v7, v8 }, $imageInit
                        const/4 v8, 0x0
                        const v9, $mask
                        invoke-direct { v6, $iconArgs }, ${iconInit.second}
                        const/4 v7, 0x0
                        new-instance v8, $OPEN
                        invoke-direct { v8 }, $OPEN-><init>()V
                        move-object v9, p0
                        const/4 v10, 0x0
                        const/16 v11, 0x50
                        invoke-static/range { v0 .. v11 }, $rowRef
                        return-void
                    """,
                )
            },
        )

        // Right after the Shut off row: Customization.menuRow(composer). The composer is the ninth argument;
        // the long before it takes two registers.
        val composer = row.startRegister + 9
        method.addInstructions(
            call + 1,
            "invoke-static/range { v$composer .. v$composer }, $WAZE_CUSTOMIZATION->menuRow(Ljava/lang/Object;)V",
        )
    }
}

private const val COMPOSER = "Landroidx/compose/runtime/Composer;"

/** Whether this method loads [literal] as a constant. */
private fun com.android.tools.smali.dexlib2.iface.Method.loadsLiteral(literal: Int) =
    implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == literal } == true

/** The "Shut off" label's resource id: R.string's field (kept in Waze's dex), else the resource table's. */
private fun BytecodePatchContext.shutOffLabel(): Int {
    val field = classDefByOrNull(WAZE_STRINGS)?.staticFields?.singleOrNull { it.name == SHUT_OFF_NAME }
    (field?.initialValue as? IntEncodedValue)?.let { return it.value }
    return try {
        resourceId(ResourceType.STRING, SHUT_OFF_NAME).toInt()
    } catch (e: Exception) {
        throw PatchException("string $SHUT_OFF_NAME not found")
    }
}

/** Lists [feature] on the Customization screen. Needs [wazeCustomizationPatch]. */
internal fun BytecodePatchContext.declareWazeToggle(feature: String) {
    val declared = mutableClassDefBy(WAZE_CUSTOMIZATION).methods.single { it.name == "uaDeclared" }
    val already = declared.implementation!!.instructions.any {
        it.opcode == Opcode.CONST_STRING && (it as ReferenceInstruction).reference.toString() == feature
    }
    if (already) return
    declared.addInstructions(
        0,
        """
            const-string v0, "$feature"
            invoke-static { v0 }, $WAZE_CUSTOMIZATION->feature(Ljava/lang/String;)V
        """,
    )
}
