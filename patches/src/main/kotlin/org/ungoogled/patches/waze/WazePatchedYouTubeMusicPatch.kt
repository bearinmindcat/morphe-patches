package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.MusicApps, the runtime half (extensions/waze). */
private const val MUSIC_APPS = "Lorg/ungoogled/waze/MusicApps;"

/** The audio SDK's partner list, as Waze's native side hands it over (a JNI class; its fields keep their names). */
private const val SDK_CONFIGURATION = "Lcom/waze/sdk/SdkConfiguration;"

/**
 * SdkConfiguration.updateAppList(): reads the partner list from the native side (NativeManager's
 * configGetSdkConfigurationNTV) and keys it by package name -- the list the audio player offers and the
 * one SdkService checks a connecting app's package name against. The class's only such method.
 */
private object UpdateAppListFingerprint : Fingerprint(
    definingClass = SDK_CONFIGURATION,
    returnType = "V",
    parameters = emptyList(),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.any {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "configGetSdkConfigurationNTV" && ref.returnType == SDK_CONFIGURATION
        } == true
    },
)

/** SdkConfiguration.getCustomizedAppIconUrl(Resources, String): a partner's icon on Waze's server, by package name. */
private object PartnerIconUrlFingerprint : Fingerprint(
    definingClass = SDK_CONFIGURATION,
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/res/Resources;", "Ljava/lang/String;"),
    strings = listOf("%s_%s.png?date=%s"),
)

@Suppress("unused")
val wazePatchedYouTubeMusicPatch = bytecodePatch(
    name = "Patched YouTube Music",
    description = "Lets Waze's audio player use YouTube Music patched with Morphe or ReVanced.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The extension, the Application hook and a switch on Waze's Customization screen.
    dependsOn(wazeCustomizationPatch)

    execute {
        // Right after the native side hands the list over: MusicApps.partners(list) adds the renamed partners.
        UpdateAppListFingerprint.method.apply {
            val code = implementation!!.instructions.toList()
            val call = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "configGetSdkConfigurationNTV"
            }
            val result = code.getOrNull(call + 1)
            if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException("SdkConfiguration.updateAppList: the partner list's move-result not found")
            }
            val list = (result as OneRegisterInstruction).registerA
            addInstruction(call + 2, "invoke-static/range { v$list .. v$list }, $MUSIC_APPS->partners(Ljava/lang/Object;)V")
        }

        // Icons come from Waze's server under the partner's own package name.
        PartnerIconUrlFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $MUSIC_APPS->iconPackage(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """,
        )

        WazeApplicationCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $MUSIC_APPS->init(Landroid/app/Application;)V",
        )
        declareWazeToggle(MUSIC_FEATURE)
    }
}

private const val MUSIC_FEATURE = "patchedYouTubeMusic"
