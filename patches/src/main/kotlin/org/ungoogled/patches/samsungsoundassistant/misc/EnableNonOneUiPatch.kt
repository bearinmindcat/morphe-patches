package org.ungoogled.patches.samsungsoundassistant.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungsoundassistant.SamsungSoundAssistantConstants.COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT
import org.w3c.dom.Element

internal const val SAMSUNG_SOUND_ASSISTANT_EXTENSION = "extensions/samsungsoundassistant.mpe"

/**
 * Individual app volumes off One UI listen for the audio sessions apps announce, which Android hands only to a running
 * app: a small foreground service (special use) does the listening, and a receiver brings it back after a reboot.
 */
private val appVolumesManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.samsungsoundassistant.AppVolumeService")
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "specialUse")
                    appendChild(
                        manifest.createElement("property").apply {
                            setAttribute("android:name", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                            setAttribute("android:value", "Individual app volumes: listens for audio sessions apps announce")
                        },
                    )
                },
            )
            application.appendChild(
                manifest.createElement("receiver").apply {
                    setAttribute("android:name", "org.ungoogled.samsungsoundassistant.AppVolumeBoot")
                    setAttribute("android:exported", "false")
                    appendChild(
                        manifest.createElement("intent-filter").apply {
                            for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")) {
                                appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
                            }
                        },
                    )
                },
            )
        }
    }
}

/** Sends Sound Assistant's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_SOUND_ASSISTANT_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungsoundassistant/OneUi;", "Lorg/ungoogled/samsungsoundassistant/Sem;",
        )
        java.util.logging.Logger.getLogger("SoundAssistant").info("One UI members: $calls calls, $fields fields")
        if (calls == 0) throw PatchException("no One UI calls found")
    }
}

/**
 * Off One UI the app dies at once on SemSoundAssistantManager, the One UI service every feature goes through. The
 * extension carries stand-ins for the 12 One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs Sound Assistant on non-One UI ROMs, where only Individual app volumes work.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT)
    dependsOn(oneUiMembersPatch, appVolumesManifestPatch)
    extendWith(SAMSUNG_SOUND_ASSISTANT_EXTENSION)
}
