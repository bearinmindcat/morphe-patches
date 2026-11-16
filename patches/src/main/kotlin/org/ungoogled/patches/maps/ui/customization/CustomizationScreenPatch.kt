package org.ungoogled.patches.maps.ui.customization

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.appLanguagePatch
import org.ungoogled.patches.maps.ui.mapsId
import org.ungoogled.patches.maps.ui.screenHostPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.ui.CustomizationActivity"
private const val TITLE = "Customization"
/** The row's click listener: opens the Customization screen with the sheet left open behind it. */
private const val OPEN = "Lorg/ungoogled/ui/CustomizationActivity\$Open;"

/** org.ungoogled.ui.SettingsRow: Customization as a row of Maps' Settings page. */
private const val SETTINGS_ROW = "Lorg/ungoogled/ui/SettingsRow;"
/** The Settings page's own row type; its name is not obfuscated. */
private const val SETTINGS_PREFERENCE = "Lcom/google/android/apps/gmm/settings/CustomSettingsPreference;"
private const val PREFERENCE = "Landroidx/preference/Preference;"

/** The Settings pages (new and legacy): Maps' only fragments with a mirroring settings helper. */
private object SettingsPagesFingerprint : Fingerprint(
    filters = listOf(string("mirroringConfigurationSettingsUiHelper")),
)

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

@Suppress("unused")
val customizationScreenPatch = bytecodePatch(
    name = "Customization screen",
    description = "Adds a Customization row under Settings on the account sheet and at the top of Settings, with " +
        "switches for the patches here that can be turned back off inside the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // appLanguagePatch: Maps' theme without Android's per-app night mode, which lost the app language (issue #198).
    dependsOn(sharedExtensionPatch, activityContextHookPatch, screenHostPatch, customizationManifestPatch, appLanguagePatch)

    execute {
        // A row of our own on the sheet's row holder, built the way Maps builds "Your data in Maps",
        // right after Settings in both of the sheet's builders. The sheet's own rows are left alone --
        // this used to take that row's builder over, which forced Trim account menu on signed-in users
        // who want those rows (issue #24). Trim account menu removes rows one by one, never this one.
        accountSheet().apply {
            val editIcon = mapsId(ResourceType.DRAWABLE, "quantum_gm_ic_edit_vd_theme_24")
            addRowBuilder("uaCustomizationRow", TITLE, editIcon, OPEN, listenerTakesContext = true)
            placeRow("uaCustomizationRow")
        }

        // Issue #164: the same screen as the first row of Maps' own Settings page. The page builds its rows in
        // code, each `screen.add(new CustomSettingsPreference(context, key, title, summary, icon))`; right before
        // the first add, the extension adds ours (made like that row, with its icon). Its tap is answered in the
        // page's preference click handler before the page's own keys.
        val page = SettingsPagesFingerprint.matchAll().map { it.classDef.type }.distinct().mapNotNull { type ->
            mutableClassDefBy(type).takeIf { c ->
                c.methods.any { m -> m.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == SETTINGS_PREFERENCE } == true }
            }
        }.singleOrNull() ?: throw PatchException("the Settings page that builds its rows in code not found")
        val build = page.methods.singleOrNull { m ->
            m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;", "Ljava/lang/String;") &&
                m.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == SETTINGS_PREFERENCE } == true
        } ?: throw PatchException("${page.type}: its rows' builder not found")
        val code = build.implementation!!.instructions
        val firstRow = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == SETTINGS_PREFERENCE }
        val add = (firstRow until code.size).firstOrNull { i ->
            code[i].opcode == Opcode.INVOKE_VIRTUAL && ((code[i] as ReferenceInstruction).reference as MethodReference).let {
                it.parameterTypes.map(CharSequence::toString) == listOf(PREFERENCE) && it.definingClass.startsWith("Landroidx/preference/Preference")
            }
        } ?: throw PatchException("${page.type}: its first row is not added to the page")
        val addCall = code[add] as FiveRegisterInstruction
        val addName = ((code[add] as ReferenceInstruction).reference as MethodReference).name
        if (addCall.registerC > 15 || addCall.registerD > 15) throw PatchException("${page.type}: the first row's add is out of reach")
        build.addInstructions(add, "invoke-static { v${addCall.registerC}, v${addCall.registerD} }, $SETTINGS_ROW->add(Ljava/lang/Object;Ljava/lang/Object;)V")
        mutableClassDefBy(SETTINGS_ROW).methods.single { it.name == "addMethod" && it.parameterTypes.isEmpty() }.let { getter ->
            val first = getter.implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("$SETTINGS_ROW->addMethod() no longer starts with const-string")
            getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$addName\"")
        }
        val click = page.methods.singleOrNull { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf(PREFERENCE)
        } ?: throw PatchException("${page.type}: its row click handler not found")
        if (click.implementation!!.registerCount < 3) throw PatchException("${page.type}: its row click handler has no local register")
        click.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $SETTINGS_ROW->clicked(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :page_row
                return v0
            """,
            ExternalLabel("page_row", click.implementation!!.instructions.first()),
        )
    }
}
