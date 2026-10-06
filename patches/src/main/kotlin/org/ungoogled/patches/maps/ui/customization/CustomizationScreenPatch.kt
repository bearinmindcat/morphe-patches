package org.ungoogled.patches.maps.ui.customization

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.ui.CustomizationActivity"
private const val TITLE = "Customization"
/** drawable/quantum_gm_ic_edit_vd_theme_24 in this build. */
private const val EDIT_ICON = 0x7f080bb4
/** The row's click listener: opens the Customization screen with the sheet left open behind it. */
private const val OPEN = "Lorg/ungoogled/ui/CustomizationActivity\$Open;"

/** Declares the screen; it is launched by explicit class name from inside the app only. */
private val customizationManifestPatch = resourcePatch(
    description = "Declares the Customization screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", TITLE)
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
        }
    }
}

/**
 * A sheet builder's Settings row: built by `bsoa.aB(context, listener)` and added
 * to the list straight away. Whatever follows depends on Trim account menu (Help &
 * feedback, or the list's finalize), so nothing after the add is matched.
 */
private fun settingsRowFingerprint(returnType: String, parameters: List<String>) = Fingerprint(
    returnType = returnType,
    parameters = parameters,
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, definingClass = "Lbsoa;", name = "aB"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, definingClass = "Lbwxy;", name = "i", location = MatchAfterImmediately()),
    ),
)
private val modernSettingsRowFingerprint = settingsRowFingerprint("Lbrhh;", listOf("Lafmm;"))
private val legacySettingsRowFingerprint = settingsRowFingerprint("Lbrfb;", listOf("Z"))

@Suppress("unused")
val customizationScreenPatch = bytecodePatch(
    name = "Customization screen",
    description = "Adds a Customization row under Settings on the account sheet, with switches for the " +
        "patches here that can be turned back off inside the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, customizationManifestPatch)

    execute {
        // 1. A row builder of our own on the sheet's row holder, built the way Maps builds
        //    "Your data in Maps" (olr.a()), so the sheet's own rows are left alone -- this
        //    used to take that row's builder over, which forced Trim account menu on
        //    signed-in users who want those rows (issue #24). Its action id must be unique
        //    on the sheet (Maps throws "appears in more than one action"), so it is generated.
        val rowHolder = mutableClassDefBy("Lolr;")
        val template = rowHolder.methods.single { it.name == "a" && it.returnType == "Lbrmi;" && it.parameterTypes.isEmpty() }
        val templateRefs = template.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        listOf(
            "Lolr;->a:Lnxb;", "Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;",
            "Lbrmi;->a()Lbrmg;", "Lbrmg;->c(I)V", "Lbrmg;->d(Ljava/lang/String;)V", "Lbrmg;->f(I)V",
            "Lbrmg;->e(Landroid/view/View\$OnClickListener;)V", "Lbrmf;->d:Lbrmf;", "Lbrmg;->a()Lbrmi;",
        ).forEach { if (it !in templateRefs) throw PatchException("account sheet row builder no longer uses $it") }
        rowHolder.methods.add(
            ImmutableMethod(
                rowHolder.type, "uaCustomizationRow", emptyList(), "Lbrmi;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(6),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, Lolr;->a:Lnxb;
                        const v1, $EDIT_ICON
                        invoke-static { v0, v1 }, Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;
                        move-result-object v1
                        invoke-static { }, Lbrmi;->a()Lbrmg;
                        move-result-object v3
                        invoke-static { }, Landroid/view/View;->generateViewId()I
                        move-result v4
                        invoke-virtual { v3, v4 }, Lbrmg;->c(I)V
                        iput-object v1, v3, Lbrmg;->a:Landroid/graphics/drawable/Drawable;
                        const-string v4, "$TITLE"
                        invoke-virtual { v3, v4 }, Lbrmg;->d(Ljava/lang/String;)V
                        const v4, 0x161a8
                        invoke-virtual { v3, v4 }, Lbrmg;->f(I)V
                        new-instance v2, $OPEN
                        invoke-direct { v2, v0 }, $OPEN-><init>(Landroid/content/Context;)V
                        invoke-virtual { v3, v2 }, Lbrmg;->e(Landroid/view/View${'$'}OnClickListener;)V
                        sget-object v4, Lbrmf;->d:Lbrmf;
                        iput-object v4, v3, Lbrmg;->d:Lbrmf;
                        invoke-virtual { v3 }, Lbrmg;->a()Lbrmi;
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )

        // 2. Add it right after Settings in both of the sheet's builders. The Settings row's
        //    own listener is `new Lolq(holder, case)`, which names the row holder; the row's
        //    result register is dead after its add (the next row writes it before any read),
        //    so it carries ours. Trim account menu removes rows one by one, never this one.
        fun addAfterSettings(fp: Fingerprint) {
            val method = fp.method
            val instructions = method.implementation!!.instructions
            val build = fp.instructionMatches[0].index
            val add = fp.instructionMatches[2].index
            val listener = instructions.subList(0, build).lastOrNull {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                    r.definingClass == "Lolq;" && r.name == "<init>"
                } == true
            } as? Instruction35c ?: throw PatchException("Settings row listener not found in ${method.definingClass}")
            val holder = listener.registerD
            val row = (instructions[fp.instructionMatches[1].index] as OneRegisterInstruction).registerA
            val list = (instructions[add] as Instruction35c).registerC
            if (holder > 15 || row > 15) throw PatchException("Settings row registers out of move-object range: v$holder, v$row")
            method.addInstructionsAtLabel(
                add + 1,
                """
                    move-object v$row, v$holder
                    check-cast v$row, Lolr;
                    invoke-virtual { v$row }, Lolr;->uaCustomizationRow()Lbrmi;
                    move-result-object v$row
                    invoke-virtual { v$list, v$row }, Lbwxy;->i(Ljava/lang/Object;)V
                """,
            )
        }
        addAfterSettings(modernSettingsRowFingerprint)
        addAfterSettings(legacySettingsRowFingerprint)
    }
}
