package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** org.ungoogled.waze.Customization, the runtime half (extensions/waze). */
internal const val WAZE_CUSTOMIZATION = "Lorg/ungoogled/waze/Customization;"
private const val OPEN = "Lorg/ungoogled/waze/Customization\$Open;"

/** Waze's copy of Kotlin's Function0: the side menu's rows take their click handler as one. */
private const val FUNCTION0 = "Lh/g/a/a;"

/** The side menu's row composable: label, modifier, two flags, a long, icon, trailing, onClick, composer, changed, default. */
private const val MENU_ROW =
    "Lcom/waze/aa/a/a/g;->b(Ljava/lang/String;Landroidx/compose/ui/Modifier;ZZJLcom/waze/aa/a/a/a;" +
        "Lcom/waze/aa/a/a/j;Lh/g/a/a;Landroidx/compose/runtime/Composer;II)V"
private const val MENU = "Lcom/waze/ui/n/ac;"

/** string/MAIN_MENU_SHUT_DOWN_ANDROID, "Shut off", in Waze 5.24.5.0. */
private const val SHUT_OFF_LABEL = 0x7f1207cf

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
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(1),
            ).toMutable().apply { addInstructions(0, "return-void") },
        )

        // The row's click handler is the extension's Open, made a Function0 here (its invoke() is there).
        val open = mutableClassDefBy(OPEN)
        if (open.methods.none { it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;" }) {
            throw PatchException("$OPEN.invoke() not found")
        }
        if (FUNCTION0 !in open.interfaces) open.interfaces.add(FUNCTION0)
        val function0 = classDefBy(FUNCTION0)
        if (function0.methods.singleOrNull()?.let { it.name == "invoke" && it.parameterTypes.isEmpty() } != true) {
            throw PatchException("$FUNCTION0 is not Function0 any more")
        }

        // Customization.menuRow(composer): the row itself, drawn by Waze's own row composable, with the
        // same defaults mask as the Shut off row (0x50: the long and the trailing slot left default).
        customization.methods.add(
            ImmutableMethod(
                WAZE_CUSTOMIZATION, "menuRow",
                listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(13),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p0, Landroidx/compose/runtime/Composer;
                        const-string v0, "Customization"
                        sget-object v1, Landroidx/compose/ui/Modifier;->Companion:Landroidx/compose/ui/Modifier${'$'}Companion;
                        check-cast v1, Landroidx/compose/ui/Modifier;
                        const/4 v2, 0x0
                        const/4 v3, 0x0
                        const-wide/16 v4, 0x0
                        new-instance v6, Lcom/waze/aa/a/a/a;
                        new-instance v7, Lcom/waze/ui/aa/b;
                        invoke-static { }, $WAZE_CUSTOMIZATION->iconRes()I
                        move-result v8
                        invoke-direct { v7, v8 }, Lcom/waze/ui/aa/b;-><init>(I)V
                        const/4 v8, 0x0
                        const/4 v9, 0x6
                        invoke-direct { v6, v7, v8, v9 }, Lcom/waze/aa/a/a/a;-><init>(Lcom/waze/ui/aa/i;Lcom/waze/aa/a/t;I)V
                        const/4 v7, 0x0
                        new-instance v8, $OPEN
                        invoke-direct { v8 }, $OPEN-><init>()V
                        move-object v9, p0
                        const/4 v10, 0x0
                        const/16 v11, 0x50
                        invoke-static/range { v0 .. v11 }, $MENU_ROW
                        return-void
                    """,
                )
            },
        )

        // Right after the Shut off row: Customization.menuRow(composer).
        val menu = mutableClassDefBy(MENU)
        val method = menu.methods.singleOrNull { m ->
            m.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == SHUT_OFF_LABEL } == true
        } ?: throw PatchException("side menu with the Shut off row not found")
        val ins = method.implementation!!.instructions.toList()
        val label = ins.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == SHUT_OFF_LABEL }
        val call = (label + 1 until ins.size).firstOrNull {
            ((ins[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == MENU_ROW
        } ?: throw PatchException("Shut off row call not found")
        val row = ins[call] as? RegisterRangeInstruction
        if (row == null || row.opcode != Opcode.INVOKE_STATIC_RANGE || row.registerCount != 12) {
            throw PatchException("Shut off row call changed shape")
        }
        // The composer is the ninth argument; the long before it takes two registers.
        val composer = row.startRegister + 9
        method.addInstructions(
            call + 1,
            "invoke-static/range { v$composer .. v$composer }, $WAZE_CUSTOMIZATION->menuRow(Ljava/lang/Object;)V",
        )
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
