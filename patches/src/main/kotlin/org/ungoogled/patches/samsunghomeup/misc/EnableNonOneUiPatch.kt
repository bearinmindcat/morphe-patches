package org.ungoogled.patches.samsunghomeup.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsunghomeup.SamsungHomeUpConstants.COMPATIBILITY_SAMSUNG_HOME_UP
import org.w3c.dom.Element

internal const val SAMSUNG_HOME_UP_EXTENSION = "extensions/samsunghomeup.mpe"

private const val EXT = "Lorg/ungoogled/samsunghomeup"
private const val PROVIDER = "com.samsung.android.app.homestar.common.HomestarProvider"

private val log = java.util.logging.Logger.getLogger("HomeUp")

/**
 * Home Up 18.0 asks for Android 16, but what it adds to One UI Home runs on Android 15 like the One UI Home port.
 * Its settings provider asks callers for a permission One UI Home only gets when Home Up was installed first: the
 * provider checks the caller's signature itself instead (see Access), so the install order does not matter.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val sdk = manifest.getElementsByTagName("uses-sdk").item(0) as? Element
                ?: throw PatchException("no <uses-sdk> in the manifest")
            val min = sdk.getAttribute("android:minSdkVersion").toIntOrNull()
                ?: throw PatchException("no minSdkVersion in the manifest")
            if (min > 35) sdk.setAttribute("android:minSdkVersion", "35")

            val providers = manifest.getElementsByTagName("provider")
            val provider = (0 until providers.length).map { providers.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == PROVIDER }
                ?: throw PatchException("no $PROVIDER in the manifest")
            provider.removeAttribute("android:permission")
        }
    }
}

/** Sends Home Up's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_HOME_UP_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        log.info("One UI members: $calls calls, $fields fields")
        if (calls < 20) throw PatchException("only $calls One UI calls found")
    }
}

/** Only One UI Home and Home Up itself (same signature) may read or change Home Up's settings. */
private val providerAccessPatch = bytecodePatch(
    description = "Lets only apps signed like Home Up use its settings provider.",
) {
    extendWith(SAMSUNG_HOME_UP_EXTENSION)

    execute {
        val call = HomestarProviderCallFingerprint.method
        call.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $EXT/Access;->allowed(Landroid/content/ContentProvider;)Z
                move-result v0
                if-nez v0, :allowed
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("allowed", call.getInstruction(0)),
        )
    }
}

/**
 * Home Up's settings reach One UI Home as content change notifications, which Android holds back 10 seconds for a
 * home app that is not the system's recents app: they are sent without that delay (see Changes).
 */
private val changesPatch = bytecodePatch(
    description = "Sends Home Up's settings changes to One UI Home without Android's background delay.",
) {
    extendWith(SAMSUNG_HOME_UP_EXTENSION)

    execute {
        val notifies = redirectCalls(
            mapOf(
                "Landroid/content/ContentResolver;->notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V" to
                    "$EXT/Changes;->notifyChange(Landroid/content/ContentResolver;Landroid/net/Uri;Landroid/database/ContentObserver;)V",
            ),
        )
        log.info("change notifications: $notifies")
        // Measured against 18.0.00.24: 4 (the settings wrapper and the home screen setting dialogs).
        if (notifies == 0) throw PatchException("Home Up's change notifications were not found")
    }
}

/**
 * Task changer, Gesture settings, Edge panel and Share Manager change parts of One UI itself (the recents screen and
 * navigation gestures, the edge panel service, the share sheet), which nothing reads elsewhere: their tiles leave the
 * main screen where the phone lacks them (see Modules). The Edge panel and Share Manager screens, which other apps
 * can open, close with a note there instead of crashing.
 */
private val oneUiModulesPatch = bytecodePatch(
    description = "Hides the Home Up modules that change One UI itself where the phone does not have it.",
) {
    extendWith(SAMSUNG_HOME_UP_EXTENSION)

    execute {
        val main = MainScreenCreateFingerprint.method
        val listeners = main.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_DIRECT &&
                ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.definingClass == main.definingClass && r.name == "registerListeners" && r.parameterTypes.isEmpty()
                }
        }
        if (listeners < 0) throw PatchException("MainActivity.onCreate does not set up its listeners")
        val activity = main.getInstruction<FiveRegisterInstruction>(listeners).registerC
        main.addInstruction(
            listeners + 1,
            "invoke-static { v$activity }, $EXT/Modules;->mainScreen(Landroid/app/Activity;)V",
        )

        for ((fingerprint, module) in listOf(
            EdgePanelCreateFingerprint to "edgepanel",
            ShareManagerCreateFingerprint to "sharemanager",
        )) {
            closeWithoutModule(fingerprint.method, module)
        }

        AppsEdgeEnabledFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXT/Modules;->appsEdgeEnabled(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        )
    }
}

/** Right after onCreate's super call: where the phone lacks the module the screen closes (see Modules.closes). */
private fun closeWithoutModule(method: MutableMethod, module: String) {
    val first = method.getInstruction<ReferenceInstruction>(0)
    val superCall = first.reference as? MethodReference
    if (first.opcode != Opcode.INVOKE_SUPER || superCall?.name != "onCreate") {
        throw PatchException("${method.definingClass}.onCreate does not start with its super call")
    }
    method.addInstructionsWithLabels(
        1,
        """
            const-string v0, "$module"
            invoke-static { p0, v0 }, $EXT/Modules;->closes(Landroid/app/Activity;Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :keep
            invoke-virtual { p0 }, Landroid/app/Activity;->finish()V
            return-void
        """,
        ExternalLabel("keep", method.getInstruction(1)),
    )
}

/**
 * Off One UI Home Up dies at once on SemFloatingFeature, and One UI Home on a non-Samsung phone cannot load it. The
 * extension carries stand-ins for the One UI classes it calls; with One UI Home ported by this bundle and both apps
 * signed with the same key, One UI Home loads Home Up's plugins.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs Home Up on non-One UI ROMs together with the patched One UI Home.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_HOME_UP)
    dependsOn(manifestPatch, oneUiMembersPatch, providerAccessPatch, changesPatch, oneUiModulesPatch)
    extendWith(SAMSUNG_HOME_UP_EXTENSION)
}
