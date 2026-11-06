package org.ungoogled.patches.samsunghomeup.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
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
    dependsOn(manifestPatch, oneUiMembersPatch, providerAccessPatch)
    extendWith(SAMSUNG_HOME_UP_EXTENSION)
}
